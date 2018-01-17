package be.dataminded.lighthouse.datalake

import java.sql.{DriverManager, SQLException}

import org.apache.spark.sql.{DataFrame, Dataset, SaveMode}

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

/**
  * Default JDBC DataRef implementation for reading and writing to a JDBC database
  *
  * @param url Function returning the URL of the database you want to connect to. Should be in the following format
  *            jdbc:mysql://${jdbcHostname}:${jdbcPort}/${jdbcDatabase}
  * @param username Function returning the Username of the database you want to connect to
  * @param password Function returning the Password of the database you want to connect to
  * @param driver Function returning the Driver to use for the database you want to connect to, should be available in
  *               the classpath
  * @param table Function returning the Table of the database where you would like to write to.
  * @param extraProperties Additional properties to use to connect to the database
  * @param partitionColumn The column where you want to partition your data for, should contain an Integer type
  * @param numberOfPartitions Amount of partitions you want to use for reading or writing your data. If value is 0 then
  *                           batchSize is taken to decide the number of partitions. If both numberOfPartitions and
  *                           batchSize is not 0, numberOfPartitions takes preference
  * @param batchSize The amount of rows that you want to retrieve in one partition. If value is 0 number of partitions
  *                  is taken to decide the batch size
  * @param saveMode Spark sql SaveMode
  */
class JdbcDataLink(url: LazyConfig[String],
                   username: LazyConfig[String],
                   password: LazyConfig[String],
                   driver: LazyConfig[String],
                   table: LazyConfig[String],
                   extraProperties: Map[String, String] = Map.empty,
                   partitionColumn: LazyConfig[String] = "",
                   numberOfPartitions: Int = 0,
                   batchSize: Int = 50000,
                   saveMode: SaveMode = SaveMode.Append)
    extends DataLink {

  // build the connection properties with some default extra ones
  lazy val connectionProperties: Map[String, String] = {
    Map(
      "url"                      -> url(),
      "driver"                   -> driver(),
      "table"                    -> table(),
      "user"                     -> username(),
      "password"                 -> password(),
      "autoReconnect"            -> "true",
      "failOverReadOnly"         -> "false",
      "rewriteBatchedStatements" -> "true",
      "useSSL"                   -> "false",
      "zeroDateTimeBehavior"     -> "convertToNull",
      "transformedBitIsBoolean"  -> "true"
    ) ++ extraProperties
  }

  override def read(): DataFrame = {
    // Partition parameters are only applicable for read operation for now
    spark.read.jdbc(connectionProperties("url"),
                    connectionProperties("table"),
                    connectionProperties ++ partitionReadParams)
  }

  override def write[T](dataset: Dataset[T]): Unit = {
    dataset.write.mode(saveMode).jdbc(connectionProperties("url"), connectionProperties("table"), connectionProperties)
  }

  private case class Boundaries(min: Int, max: Int, count: Int)

  // The returns the minimum, maximum and total count of the partitionColumn
  private def getBoundaries(partitions: Int = 0): Try[Boundaries] = {
    Try {
      // Do this to make sure driver is loaded
      Class.forName(driver())
      val connection = DriverManager.getConnection(connectionProperties("url"), connectionProperties)
      val statement  = connection.createStatement()

      // Only perform count when partitions are not set
      val query = partitions match {
        case p if p == 0 =>
          s"select min(${partitionColumn()}) as min, max(${partitionColumn()}) as max, count(${partitionColumn()}) as count from ${table()}"
        case p if p > 0 => s"select min(${partitionColumn()}) as min, max(${partitionColumn()}) as max from ${table()}"
      }

      // Execute query, and parse based on whether we need to take count into account
      val result = (partitions, statement.execute(query), statement.getResultSet.first) match {
        case (p, true, true) if p == 0 =>
          Boundaries(statement.getResultSet.getInt("min"),
                     statement.getResultSet.getInt("max"),
                     statement.getResultSet.getInt("count"))
        case (p, true, true) if p > 0 =>
          Boundaries(statement.getResultSet.getInt("min"), statement.getResultSet.getInt("max"), 0)
        case _ => throw new SQLException("Min, max and count value could not be retrieved")
      }

      connection.close()
      result
    }
  }

  private def convertToMap(partitionColumn: String,
                           lowerBound: Int,
                           upperBound: Int,
                           numPartitions: Int): Map[String, String] = {
    Map("partitionColumn" -> partitionColumn,
        "lowerBound"      -> lowerBound.toString,
        "upperBound"      -> upperBound.toString,
        "numPartitions"   -> numPartitions.toString)
  }

  // Get the extra partition parameters
  private lazy val partitionReadParams: Map[String, String] = {
    val boundaries = getBoundaries(numberOfPartitions)

    (partitionColumn(), boundaries, numberOfPartitions, batchSize) match {
      // If no partition column is provided
      case (partition, _, _, _) if partition == null || partition.isEmpty => Map()

      // If we were not able to retrieve the data boundaries
      case (_, Failure(_), _, _) => Map()

      // When the number of partitions is set
      case (partition, Success(Boundaries(min, max, _)), numPart, _) if numPart > 0 =>
        convertToMap(partition, min, max, numPart)

      // When the batch size is set
      case (partition, Success(Boundaries(min, max, count)), _, batch) if batch > 0 =>
        convertToMap(partition, min, max, (count / batch) + 1)

      // In all other cases
      case _ => Map()
    }
  }
}
