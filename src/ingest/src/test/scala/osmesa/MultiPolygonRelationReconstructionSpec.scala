package osmesa

import java.sql.Timestamp

import geotrellis.spark.io.kryo.KryoRegistrator
import org.apache.spark.SparkConf
import org.apache.spark.serializer.KryoSerializer
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.scalatest.prop.{TableDrivenPropertyChecks, Tables}
import org.scalatest.{Matchers, PropSpec}
import osmesa.functions._
import osmesa.functions.osm._

import scala.io.Source

case class Fixture(id: Int, members: DataFrame, wkt: Seq[String])

trait SparkPoweredTables extends Tables {
  val spark: SparkSession = SparkSession
    .builder
    .config(
      /* Settings compatible with both local and EMR execution */
      new SparkConf()
        .setAppName(getClass.getName)
        .setIfMissing("spark.master", "local[*]")
        .setIfMissing("spark.serializer", classOf[KryoSerializer].getName)
        .setIfMissing("spark.kryo.registrator", classOf[KryoRegistrator].getName)
    )
    .getOrCreate()

  def relation(relation: Int): (Fixture) = Fixture(relation, orc(s"relation-$relation.orc"), wkt(s"relation-$relation.wkt"))

  def orc(filename: String): DataFrame = spark.read.orc(getClass.getResource("/" + filename).getPath)

  def wkt(filename: String): Seq[String] = {
    try {
      Source.fromInputStream(getClass.getResourceAsStream("/" + filename)).getLines.toSeq match {
        case expected if expected.isEmpty => Seq("")
        case expected => expected
      }
    } catch {
      case _: Exception => Seq("[not provided]")
    }
  }

  def asWKT(relations: DataFrame): Seq[String] = {
    import relations.sparkSession.implicits._

    relations.select(ST_AsText('geom).as("wkt")).collect.map { row =>
      row.getAs[String]("wkt")
    }
  }
}

// osm2pgsql -c -d rhode_island -j -K -l rhode-island-latest.osm.pbf
// select ST_AsText(way) from planet_osm_polygon where osm_id=-333501;
// to debug / visually validate (geoms won't match exactly), load WKT into geojson.io from Meta → Load WKT String
// https://www.openstreetmap.org/relation/64420
// to find multipolygons: select osm_id from planet_osm_polygon where osm_id < 0 and ST_GeometryType(way) = 'ST_MultiPolygon' order by osm_id desc;
class MultiPolygonRelationExamples extends SparkPoweredTables {
  def examples = Table("multipolygon relation",
    relation(333501), // unordered, single polygon with 1 hole
    relation(393502), // single polygon, multiple outer parts, no holes
    relation(1949938), // unordered, single polygon with multiple holes
    relation(3105056), // multiple unordered outer parts in varying directions
    relation(2580685), // multipolygon: 2 polygons, one with 1 hole
    relation(3080946), // multipolygon: many polygons, no holes
    relation(5448156), // multipolygon made up of parcels
    relation(5448691), // multipolygon made up of parcels
    relation(6710544), // complex multipolygon
    relation(191199), // 4 segments; 2 are components of another (thus duplicates)
    relation(61315), // incomplete member list (sourced from an extract of a neighboring state)
    relation(2554903), // boundary w/ admin_centre + label node members
    relation(191204) // no members
  )
}

class MultiPolygonRelationReconstructionSpec extends PropSpec with TableDrivenPropertyChecks with Matchers {
  property("should match expected WKT") {
    new MultiPolygonRelationExamples {
      forAll(examples) { fixture =>
        import fixture.members.sparkSession.implicits._

        val actual = asWKT(fixture.members.withColumn("version", lit(1L)).withColumn("timestamp", lit(Timestamp.valueOf("2001-01-01 00:00:00")))
          .groupBy('changeset, 'id, 'version, 'timestamp)
          .agg(collectRelation('id, 'version, 'timestamp, 'type, 'role, 'geom) as 'geom))

        val expected = fixture.wkt

        try {
          actual should ===(expected)
        } catch {
          case e: Throwable =>
            println(s"${fixture.id} actual:")
            actual.foreach(println)
            println(s"${fixture.id} expected:")
            expected.foreach(println)

            throw e
        }
      }
    }
  }
}
