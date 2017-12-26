import bwapi.{Unit => ScUnit, _}
import bwta.BWTA
import scalaz._
import Scalaz._
import scala.collection.JavaConverters._

object TestBot {
  def main(args: Array[String]): Unit =
    new TestBot().run()
}

class TestBot extends DefaultBWListener {
  val mirror = new Mirror()
  var game: Game = _
  var self: Player = _

  def run(): Unit = {
    mirror.getModule.setEventListener(this)
    mirror.startGame()
  }

  override def onUnitCreate(unit: ScUnit): Unit = {
    System.out.println("New unit " + unit.getType)
  }

  override def onStart(): Unit = {
    game = mirror.getGame
    self = game.self()

    //Use BWTA to analyze map
    //This may take a few minutes if the map is processed first time!
    System.out.println("Analyzing map...")
    BWTA.readMap()
    BWTA.analyze()

    game.enableFlag(1) //Enables control of AI
    game.setLocalSpeed(5) //20 is tournament speed

    System.out.println("Map data ready")
  }


  def trainWorkers(player: Player): Int = {
    val maxWorkers = 15 * player.getUnits.asScala.count(_.getType == UnitType.Protoss_Nexus)
    val workerCount = getWorkers(player).size
    val targetWorkers = maxWorkers - workerCount
    val potentialWorkers = player.minerals / 50
    player
      .getUnits
      .asScala
      .filter(u => u.getType == UnitType.Protoss_Nexus)
      .filter(u => u.isIdle)
      .take(Math.min(targetWorkers, potentialWorkers))
      .foldRight(0)((u: ScUnit, i: Int) => {
        u.train(UnitType.Protoss_Probe)
        i + 1
      })
  }

  def getBuildTile(game: Game, buildingType: UnitType, builder: ScUnit, searchStartPoint: TilePosition): Option[TilePosition] = {
    val maxDist = 6
    val stopDist = 40
    if(buildingType.isRefinery) {
      game
        .neutral()
        .getUnits
        .asScala
        .filter(_.getType == UnitType.Resource_Vespene_Geyser)
        .filter(u => Math.abs(u.getTilePosition.getX - searchStartPoint.getX) < stopDist)
        .find(u => Math.abs(u.getTilePosition.getY - searchStartPoint.getY) < stopDist)
        .map(_.getTilePosition)
    } else {
      val xs = Range(searchStartPoint.getX - maxDist, searchStartPoint.getX + maxDist)
      val ys = Range(searchStartPoint.getY - maxDist, searchStartPoint.getY + maxDist)
      val ps = for {
        x <- xs
        y <- ys
      } yield (x, y)
      ps.filter(p => game.canBuildHere(new TilePosition(p._1, p._2), buildingType, builder, false))
        .find( p => {
          !game.getAllUnits.asScala.toList.any( (u: ScUnit) => {
            (u.getID != builder.getID) && (Math.abs(u.getTilePosition.getX - p._1) < 4) && (Math.abs(u.getTilePosition.getY - p._2) < 4)
          })
        })
        .map(p => new TilePosition(p._1, p._2))
    }
  }

  def buildSupplyStructures(game: Game, player: Player): Unit = {
    if((player.supplyTotal() - player.supplyUsed() < 2) && player.minerals() >= 100) {
      val worker = player
        .getUnits
        .asScala
        .find(_.getType.isWorker)

      val pair = worker.flatMap(w => {
        val tile = getBuildTile(game, UnitType.Protoss_Pylon, w, player.getStartLocation)
        if(tile.isDefined) System.out.println("Target tile found")
        tile.map(t => (t, w))
      })
      pair.foreach{ case (tile: TilePosition, unit: ScUnit) =>
          unit.build(UnitType.Protoss_Pylon, tile)
      }
    }
  }

  def getWorkers(player: Player): Iterable[ScUnit] = player.getUnits.asScala.filter(_.getType.isWorker)

  def isNonCombatUnit(unit: ScUnit): Boolean = unit.getType.isWorker || unit.getType.isBuilding

  override def onFrame(): Unit = {
    //game.setTextSize(10);
    game.drawTextScreen(10, 10, "Playing as " + self.getName + " - " + self.getRace)
    self.getUnits.asScala.filter(u => !isNonCombatUnit(u))

    trainWorkers(self)
    buildSupplyStructures(game, self)

    self.getUnits.asScala
      .filter(_.getType.isWorker)
      .filter(_.isIdle)
      .foreach { worker =>
        val closestMineral = game.neutral.getUnits.asScala
          .filter(_.getType.isMineralField)
          .map(mineral => (mineral.getDistance(worker), mineral))
          .sortBy(_._1)
          .map(_._2)
          .headOption

        closestMineral.foreach(worker.gather)
      }
  }
}
