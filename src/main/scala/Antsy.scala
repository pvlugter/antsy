package antsy

import edu.stanford.ppl.ccstm._
import java.util.concurrent.TimeUnit
import org.fusesource.hawtdispatch.ScalaDispatch._
import scala.util.Random.{nextInt => randomInt}

object Config {
  val Dim = 80               // dimensions of square world
  val AntsSqrt = 10          // number of ants = AntsSqrt^2
  val FoodPlaces = 35        // number of places with food
  val FoodRange = 100        // range of amount of food at a place
  val PherScale = 10         // scale factor for pheromone drawing
  val AntDelay = 100         // determines how often an ant behaves (delay in milliseconds)
  val SnapshotDelay = 100    // delay between snapshots (milliseconds)
  val FrameRate = 10         // frame rate for drawing (per second)
  val EvapDelay = 1000       // the rate at which pheromone evaporation occurs (milliseconds)
  val Evaporation = 0.99f    // pheromone evaporation multiplier
}

case class Ant(dir: Int, food: Boolean = false) {
  def turn(i: Int) = copy(dir = Util.dirBound(dir + i))
  def turnAround = turn(4)
  def pickUp = copy(food = true)
  def dropOff = copy(food = false)
}

case class Cell(food: Int = 0, pher: Float = 0, ant: Option[Ant] = None, home: Boolean = false) {
  def addFood(i: Int) = copy(food = food + i)
  def addPher(x: Float) = copy(pher = pher + x)
  def alterPher(f: Float => Float) = copy(pher = f(pher))
  def putAnt(antOpt: Option[Ant]) = copy(ant = antOpt)
  def makeHome = copy(home = true)
}

object EmptyCell extends Cell

class Place(initCell: Cell = EmptyCell) {
  val cellRef = Ref(initCell)

  def cell(implicit txn: Txn): Cell = cellRef.get
  def alter(f: Cell => Cell)(implicit txn: Txn) = cellRef.transform(f)

  def food(implicit txn: Txn): Int = cell.food
  def food(i: Int)(implicit txn: Txn) = alter(_.addFood(i))
  def hasFood(implicit txn: Txn) = food > 0

  def pher(implicit txn: Txn): Float = cell.pher
  def pher(f: Float => Float)(implicit txn: Txn) = alter(_.alterPher(f))
  def trail(implicit txn: Txn) = alter(_.addPher(1))

  def ant(implicit txn: Txn): Option[Ant] = cell.ant
  def ant(f: Ant => Ant)(implicit txn: Txn): Unit = alter(_.putAnt(ant map f))
  def enter(antOpt: Option[Ant])(implicit txn: Txn): Unit = alter(_.putAnt(antOpt))
  def enter(ant: Ant)(implicit txn: Txn): Unit = enter(Some(ant))
  def leave(implicit txn: Txn) = enter(None)
  def occupied(implicit txn: Txn): Boolean = ant.isDefined

  def makeHome(implicit txn: Txn) = alter(_.makeHome)
  def home(implicit txn: Txn): Boolean = cell.home
}

object World {
  import Config._

  val homeOff = Dim / 4
  val places = Array.fill(Dim, Dim)(new Place)
  val antMovers = setup
  val evaporator = new Evaporator
  val snapshotRef = Ref(createSnapshot)

  def place(loc: (Int, Int)) = places(loc._1)(loc._2)

  def setup = atomic { implicit t =>
    for (i <- 1 to FoodPlaces) {
      place(randomInt(Dim), randomInt(Dim)) food (randomInt(FoodRange))
    }
    val homeRange = homeOff until (AntsSqrt + homeOff)
    for (x <- homeRange; y <- homeRange) yield {
      place(x, y).makeHome
      place(x, y) enter Ant(randomInt(8))
      new AntMover(x, y)
    }
  }

  def createSnapshot = atomic { implicit t =>
    Array.tabulate(Dim, Dim)(place(_, _).cell)
  }

  def snapshot = snapshotRef.single.get

  def snapshotLoop = Dispatch.loop(SnapshotDelay) {
    val newSnapshot = createSnapshot
    snapshotRef.single.set(newSnapshot)
  }

  def evaporationLoop = Dispatch.loop(EvapDelay) { evaporator.evaporate }

  def antLoop(ant: AntMover) = Dispatch.loop(AntDelay) { ant.act }

  def start = {
    antMovers foreach antLoop
    snapshotLoop
    evaporationLoop
  }
}

object Dispatch {
  def loop(delay: Long)(body: => Unit) = {
    def task: Unit = {
      body
      globalQueue.after(delay, TimeUnit.MILLISECONDS)(task)
    }
    globalQueue(task)
  }
}

object Util {
  import Config._

  def bound(b: Int, n: Int) = {
    val x = n % b
    if (x < 0) x + b else x
  }

  def dirBound(n: Int) = bound(8, n)
  def dimBound(n: Int) = bound(Dim, n)

  val dirDelta = Map(0 -> (0, -1), 1 -> (1, -1), 2 -> (1, 0), 3 -> (1, 1),
                     4 -> (0, 1), 5 -> (-1, 1), 6 -> (-1, 0), 7 -> (-1, -1))

  def deltaLoc(x: Int, y: Int, dir: Int) = {
    val (dx, dy) = dirDelta(dirBound(dir))
    (dimBound(x + dx), dimBound(y + dy))
  }

  def rankBy[A, B: Ordering](xs: Seq[A], f: A => B) =
    xs.sortBy(f).zip(Stream from 1).toMap

  def roulette(slices: Seq[Int]) = {
    val total = slices.sum
    val r = randomInt(total)
    var i, sum = 0
    while ((sum + slices(i)) <= r) {
      sum += slices(i)
      i += 1
    }
    i
  }
}

class AntMover(initLoc: (Int, Int)) {
  import World._
  import Util._

  val locRef = Ref(initLoc)

  def homing(p: Place)(implicit txn: Txn) = p.pher + (100 * (if (p.home) 0 else 1))
  def foraging(p: Place)(implicit txn: Txn) = p.pher + p.food

  def loc(implicit txn: Txn) = locRef.get
  def newLoc(l: (Int, Int))(implicit txn: Txn) = locRef.set(l)

  def act = atomic { implicit t =>
    val (x, y) = loc
    val current = place(x, y)
    for (ant <- current.ant) {
      val ahead = place(deltaLoc(x, y, ant.dir))
      if (ant.food) { // homing
        if (current.home) dropFood
        else if (ahead.home && !ahead.occupied) move
        else random(homing)
      } else { // foraging
        if (!current.home && current.hasFood) pickUpFood
        else if (!ahead.home && ahead.hasFood && !ahead.occupied) move
        else random(foraging)
      }
    }
  }

  def move(implicit txn: Txn) = {
    val (x, y) = loc
    val from = place(x, y)
    for (ant <- from.ant) {
      val toLoc = deltaLoc(x, y, ant.dir)
      val to = place(toLoc)
      to enter ant
      from.leave
      if (!from.home) from.trail
      newLoc(toLoc)
    }
  }

  def pickUpFood(implicit txn: Txn) = {
    val current = place(loc)
    current food -1
    current ant (_.pickUp.turnAround)
  }

  def dropFood(implicit txn: Txn) = {
    val current = place(loc)
    current food +1
    current ant (_.dropOff.turnAround)
  }

  def random[A](ranking: Place => A)(implicit ord: Ordering[A], txn: Txn) = {
    val (x, y) = loc
    val current = place(x, y)
    for (ant <- current.ant) {
      val delta = (turn: Int) => place(deltaLoc(x, y, ant.dir + turn))
      val ahead = delta(0)
      val aheadLeft = delta(-1)
      val aheadRight = delta(+1)
      val locations = Seq(ahead, aheadLeft, aheadRight)
      val ranks = rankBy(locations, ranking)
      val ranked = Seq(ranks(aheadLeft), (if (ahead.occupied) 0 else ranks(ahead)), ranks(aheadRight))
      val dir = roulette(ranked) - 1
      if (dir == 0) move
      else current ant (_.turn(dir))
    }
  }
}

class Evaporator {
  import Config._
  import World._

  val evaporation = (pher: Float) => pher * Evaporation

  def evaporate = for (x <- 0 until Dim; y <- 0 until Dim) {
    atomic { implicit t => place(x, y) pher evaporation }
  }
}

