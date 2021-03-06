package com.guizmaii.distances.providers

import cats.effect.{Async, IO}
import cats.temp.par.Par
import com.guizmaii.distances.Types.{LatLong, NonAmbiguousAddress, PostalCode}
import com.guizmaii.distances.providers.GoogleDistanceProvider.GoogleGeoApiContext
import monix.eval.Task
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import shapeless.CNil

import scala.concurrent.duration._
import scala.language.postfixOps

class GoogleGeoProviderSpec extends WordSpec with Matchers with ScalaFutures with BeforeAndAfterEach {

  lazy val geoContext: GoogleGeoApiContext = GoogleGeoApiContext(System.getenv().get("GOOGLE_API_KEY"))

  val lille                = LatLong(latitude = 50.6138111, longitude = 3.0423599)
  val lambersart           = LatLong(latitude = 50.65583909999999, longitude = 3.0226977)
  val harnes               = LatLong(latitude = 50.4515282, longitude = 2.9047234)
  val artiguesPresBordeaux = LatLong(latitude = 44.84034490000001, longitude = -0.4408037)

  def passTests[AIO[+ _]: Async: Par](runSync: AIO[Any] => Any): Unit = {

    val geocoder: GeoProvider[AIO] = GoogleGeoProvider[AIO](geoContext)

    /*
    Remarque Jules:
    --------------
      Les tests sont fait sur le code postal 59000.

      Pour obtenir des données à valider, effectuez la requête suivante:
        $ curl https://maps.googleapis.com/maps/api/geocode/json?components=postal_code:59000&region=eu&key=YOUR_API_KEY
     */
    "geocodePostalCode" should {
      def testGeocoder(postalCode: PostalCode, place: LatLong): Assertion =
        runSync(geocoder.geocode(postalCode)) shouldBe place

      "cache and return" should {
        "Lille" in {
          testGeocoder(PostalCode("59000"), lille)
        }
        "Lambersart" in {
          testGeocoder(PostalCode("59130"), lambersart)
        }
        "Harnes" in {
          testGeocoder(PostalCode("62440"), harnes)
        }
        "Artigues-près-Bordeaux" in {
          testGeocoder(PostalCode("33370"), artiguesPresBordeaux)
        }
      }
    }

    "geocodeNonAmbigueAddress" should {

      import kantan.csv._
      import kantan.csv.generic._
      import kantan.csv.ops._

      implicitly[CellDecoder[CNil]] // IntelliJ doesn't understand that `import kantan.csv.generic._` is required.

      final case class TestAddress(line1: String, postalCode: String, town: String, lat: String, long: String)
      object TestAddress {
        def toAddressAndLatLong(addr: TestAddress): (NonAmbiguousAddress, LatLong) =
          NonAmbiguousAddress(line1 = addr.line1, line2 = "", postalCode = addr.postalCode, town = addr.town, country = "France") -> LatLong(
            latitude = addr.lat.toDouble,
            longitude = addr.long.toDouble)
      }

      val rawData =
        s"""
           |Line1;PostalCode;Town;Lat;Long
           |5 BOULEVARD DE LA MADELEINE;75001;PARIS;48.8695813;2.3272826
           |17 rue Francois Miron;75004;Paris;48.8557984;2.3570898
           |1 RUE DANTON;75006;PARIS;48.8528005;2.3427676
           |24 rue dauphine;75006;PARIS;48.8549537;2.3393333
           |30 PLACE DE LA MADELEINE;75008;PARIS;48.8708155;2.325606
           |50 rue du Docteur Blanche;75016;Paris;48.8528274;2.2643836
           |16 RUE SAINT FIACRE  - 75002 PARIS;75002;PARIS;48.8703821;2.3459086
           |4 RUE DE SONTAY;75116;PARIS;48.8703854;2.2846272
           |7 rue Victorien Sardou;75016;Paris;48.8428041;2.2675564
           |62 avenue des champs elysee;75008;Paris;48.8708509;2.3056707
           |233 Boulevard Voltaire 75011 Paris;75011;Paris 75011;48.8512903;2.3914116
           |13 rue Henri Barbusse;92230;GENNEVILLIERS;48.9182397;2.2967879
           |35 boulevard d'Exelmans;75016;PARIS;48.84135999999999;2.2633114
           |95 avenue du General Leclerc;75014;Paris;48.8260975;2.3273668
           |12 rue de l'Assomption;75016;Paris;48.85349;2.2744602
           |108 rue de Richelieu;75002;PARIS;48.8714406;2.3398815
           |24 AVENUE MARIE ALEXIS;76370;PETIT CAUX;49.95763789999999;1.2224194
           |8 RUE FLEURS DE LYS;33370;Artigues-près-Bordeaux;44.8496786;-0.4831272
           |8 RUE des FLEURS DE LYS;33370;Artigues-près-Bordeaux;${artiguesPresBordeaux.latitude};${artiguesPresBordeaux.longitude}
           |""".stripMargin.drop(1).dropRight(1)

      val data: Seq[(NonAmbiguousAddress, LatLong)] =
        rawData.unsafeReadCsv[List, TestAddress](rfc.withHeader.withCellSeparator(';')).map(TestAddress.toAddressAndLatLong)

      def testNonAmbigueAddressGeocoder: ((NonAmbiguousAddress, LatLong)) => Unit = { (address: NonAmbiguousAddress, latLong: LatLong) =>
        s"$address should be located at $latLong}" in {
          runSync(geocoder.geocode(address)) shouldBe latLong
        }
      }.tupled

      data.foreach(testNonAmbigueAddressGeocoder.apply)
    }
  }

  "GoogleGeocoder" should {
    "pass tests with cats-effect IO" should {
      passTests[IO](_.unsafeRunSync())
    }
    "pass tests with Monix Task" should {
      import monix.execution.Scheduler.Implicits.global

      passTests[Task](_.runSyncUnsafe(10 seconds))
    }
  }

}
