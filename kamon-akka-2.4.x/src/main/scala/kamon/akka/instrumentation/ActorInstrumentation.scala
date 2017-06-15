package akka.kamon.instrumentation

import java.util.concurrent.locks.ReentrantLock

import akka.actor._
import akka.dispatch.Envelope
import akka.dispatch.sysmsg.SystemMessage
import akka.routing.RoutedActorCell
import akka.stream.Attributes
import akka.stream.stage.GraphStageLogic
import kamon.Kamon
import kamon.akka.{ActorGroupMetrics, StageMetrics}
import kamon.metric.Entity
import kamon.trace.Tracer
import kamon.util.RelativeNanoTimestamp
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation._

import scala.collection.{immutable, mutable}
import scala.ref.WeakReference
import scala.util.Properties


@Aspect
class GraphLogicInstrumentation {

  def logicInstrumentation(logic: GraphStageLogic): GraphLogicMonitor =
    logic.asInstanceOf[GraphLogicInstrumentationAware].graphLogicInstrumentation


  @Pointcut("execution(akka.stream.stage.GraphStageLogic+.new(..)) && this(logic)")
  def graphStageLogicCreation(logic: GraphStageLogic): Unit = {}


  @After("graphStageLogicCreation(logic)")
  def afterCreation(logic: GraphStageLogic): Unit = {}


  @Pointcut("execution(* akka.stream.stage.GraphStageLogic.beforePreStart(..)) && this(logic)")
  def beforePreStart(logic: GraphStageLogic): Unit = {}

  @After("beforePreStart(logic)")
  def beforeGraphPreStart(logic: GraphStageLogic):Unit = {

    val attributes = GraphLogicInstrumentation.attributesField.get(logic).asInstanceOf[Attributes]

    logic.asInstanceOf[GraphLogicInstrumentationAware].setGraphLogicInstrumentation(
      GraphLogicMonitor.createLogicMonitor(logic, attributes.nameLifted))
  }

  @Pointcut("execution(* akka.stream.stage.GraphStageLogic.push(..)) && this(logic) && args(*, elem)")
  def graphLogicPush(logic: GraphStageLogic, elem: Any): Unit = {}

  @After("graphLogicPush(logic, elem)")
  def onStagePush(logic: GraphStageLogic, elem: Any):Unit = {
    logicInstrumentation(logic).push(elem)
  }


  @Pointcut("execution(* akka.stream.stage.GraphStageLogic.grab(..)) && this(logic)")
  def graphLogicGrab(logic: GraphStageLogic): Unit = {}

  @AfterReturning(pointcut="graphLogicGrab(logic)", returning="elem")
  def onStageGrab(logic: GraphStageLogic, elem: Any):Unit = {
    logicInstrumentation(logic).grab(elem)
  }


}



object GraphLogicInstrumentation {
  private val attributesField = {
    val stageLogicClass = classOf[GraphStageLogic]

    val attributesField_ = stageLogicClass.getDeclaredField("attributes")
    attributesField_.setAccessible(true)
    attributesField_
  }


}

trait GraphLogicInstrumentationAware {
  def graphLogicInstrumentation: GraphLogicMonitor
  def setGraphLogicInstrumentation(ai: GraphLogicMonitor): Unit
}

object GraphLogicInstrumentationAware {
  def apply(): GraphLogicInstrumentationAware = new GraphLogicInstrumentationAware {
    private var _ai: GraphLogicMonitor = _

    def setGraphLogicInstrumentation(ai: GraphLogicMonitor): Unit = _ai = ai
    def graphLogicInstrumentation: GraphLogicMonitor = _ai
  }

}

@Aspect
class MetricsIntoGraphLogicMixin {

  @DeclareMixin("akka.stream.stage.GraphStageLogic+")
  def mixinActorCellMetricsToActorCell: GraphLogicInstrumentationAware = GraphLogicInstrumentationAware()

}


trait GraphLogicMonitor {
  def pull(): Unit
  def push(elem: Any): Unit
  def grab(elem: Any): Unit
  def cleanup(): Unit
}

object GraphLogicMonitor {


  def createLogicMonitor(logic: GraphStageLogic, name: Option[String]): GraphLogicMonitor = {

    new GroupMetricsTrackingLogic(Entity(s"${name.getOrElse(logic.interpreter.Name)}-${logic.interpreter.logics.indexOf(logic)}", StageMetrics.category), Kamon.metrics.entity(StageMetrics, Entity(s"${name.getOrElse(logic.interpreter.Name)}-${logic.interpreter.logics.indexOf(logic)}", StageMetrics.category)))

  }
}

class GroupMetricsTrackingLogic(entity: Entity,
  metrics: StageMetrics) extends GraphLogicMonitor {

  val activeMap =  mutable.Map.empty[Any, RelativeNanoTimestamp]


  override def pull(): Unit = {}

  override def push(elem: Any): Unit = {
    val newTime = RelativeNanoTimestamp.now
    activeMap.remove(elem).foreach(t => metrics.processingTime.record((newTime - t).nanos))

  }

  override def grab(elem: Any): Unit = activeMap.put(elem, RelativeNanoTimestamp.now)

  override def cleanup(): Unit = ???
}



@Aspect
class ActorCellInstrumentation {

  def actorInstrumentation(cell: Cell): ActorMonitor =
    cell.asInstanceOf[ActorInstrumentationAware].actorInstrumentation

  @Pointcut("execution(akka.actor.ActorCell.new(..)) && this(cell) && args(system, ref, *, *, parent)")
  def actorCellCreation(cell: Cell, system: ActorSystem, ref: ActorRef, parent: InternalActorRef): Unit = {}

  @Pointcut("execution(akka.actor.UnstartedCell.new(..)) && this(cell) && args(system, ref, *, parent)")
  def repointableActorRefCreation(cell: Cell, system: ActorSystem, ref: ActorRef, parent: InternalActorRef): Unit = {}

  @After("actorCellCreation(cell, system, ref, parent)")
  def afterCreation(cell: Cell, system: ActorSystem, ref: ActorRef, parent: ActorRef): Unit = {
    cell.asInstanceOf[ActorInstrumentationAware].setActorInstrumentation(
      ActorMonitor.createActorMonitor(cell, system, ref, parent, true))
  }

  @After("repointableActorRefCreation(cell, system, ref, parent)")
  def afterRepointableActorRefCreation(cell: Cell, system: ActorSystem, ref: ActorRef, parent: ActorRef): Unit = {
    cell.asInstanceOf[ActorInstrumentationAware].setActorInstrumentation(
      ActorMonitor.createActorMonitor(cell, system, ref, parent, false))
  }

  @Pointcut("execution(* akka.actor.ActorCell.invoke(*)) && this(cell) && args(envelope)")
  def invokingActorBehaviourAtActorCell(cell: ActorCell, envelope: Envelope) = {}

  @Around("invokingActorBehaviourAtActorCell(cell, envelope)")
  def aroundBehaviourInvoke(pjp: ProceedingJoinPoint, cell: ActorCell, envelope: Envelope): Any = {
    actorInstrumentation(cell).processMessage(pjp, envelope.asInstanceOf[InstrumentedEnvelope].envelopeContext())
  }

  /**
   *
   *
   */

  @Pointcut("execution(* akka.actor.ActorCell.sendMessage(*)) && this(cell) && args(envelope)")
  def sendMessageInActorCell(cell: Cell, envelope: Envelope): Unit = {}

  @Pointcut("execution(* akka.actor.UnstartedCell.sendMessage(*)) && this(cell) && args(envelope)")
  def sendMessageInUnstartedActorCell(cell: Cell, envelope: Envelope): Unit = {}

  @Before("sendMessageInActorCell(cell, envelope)")
  def afterSendMessageInActorCell(cell: Cell, envelope: Envelope): Unit = {
    setEnvelopeContext(cell, envelope)
  }

  @Before("sendMessageInUnstartedActorCell(cell, envelope)")
  def afterSendMessageInUnstartedActorCell(cell: Cell, envelope: Envelope): Unit = {
    setEnvelopeContext(cell, envelope)
  }

  private def setEnvelopeContext(cell: Cell, envelope: Envelope): Unit = {
    envelope.asInstanceOf[InstrumentedEnvelope].setEnvelopeContext(
      actorInstrumentation(cell).captureEnvelopeContext())
  }

  @Pointcut("execution(* akka.actor.UnstartedCell.replaceWith(*)) && this(unStartedCell) && args(cell)")
  def replaceWithInRepointableActorRef(unStartedCell: UnstartedCell, cell: Cell): Unit = {}

  @Around("replaceWithInRepointableActorRef(unStartedCell, cell)")
  def aroundReplaceWithInRepointableActorRef(pjp: ProceedingJoinPoint, unStartedCell: UnstartedCell, cell: Cell): Unit = {
    import ActorCellInstrumentation._
    // TODO: Find a way to do this without resorting to reflection and, even better, without copy/pasting the Akka Code!
    val queue = unstartedCellQueueField.get(unStartedCell).asInstanceOf[java.util.LinkedList[_]]
    val lock = unstartedCellLockField.get(unStartedCell).asInstanceOf[ReentrantLock]

    def locked[T](body: ⇒ T): T = {
      lock.lock()
      try body finally lock.unlock()
    }

    locked {
      try {
        while (!queue.isEmpty) {
          queue.poll() match {
            case s: SystemMessage ⇒ cell.sendSystemMessage(s) // TODO: ============= CHECK SYSTEM MESSAGESSSSS =========
            case e: Envelope with InstrumentedEnvelope ⇒
              Tracer.withContext(e.envelopeContext().context) {
                cell.sendMessage(e)
              }
          }
        }
      } finally {
        unStartedCell.self.swapCell(cell)
      }
    }
  }

  /**
   *
   */

  @Pointcut("execution(* akka.actor.ActorCell.stop()) && this(cell)")
  def actorStop(cell: ActorCell): Unit = {}

  @After("actorStop(cell)")
  def afterStop(cell: ActorCell): Unit = {
    actorInstrumentation(cell).cleanup()

    // The Stop can't be captured from the RoutedActorCell so we need to put this piece of cleanup here.
    if (cell.isInstanceOf[RoutedActorCell]) {
      cell.asInstanceOf[RouterInstrumentationAware].routerInstrumentation.cleanup()
    }
  }

  @Pointcut("execution(* akka.actor.ActorCell.handleInvokeFailure(..)) && this(cell) && args(childrenNotToSuspend, failure)")
  def actorInvokeFailure(cell: ActorCell, childrenNotToSuspend: immutable.Iterable[ActorRef], failure: Throwable): Unit = {}

  @Before("actorInvokeFailure(cell, childrenNotToSuspend, failure)")
  def beforeInvokeFailure(cell: ActorCell, childrenNotToSuspend: immutable.Iterable[ActorRef], failure: Throwable): Unit = {
    actorInstrumentation(cell).processFailure(failure)
  }
}

object ActorCellInstrumentation {
  private val (unstartedCellQueueField, unstartedCellLockField) = {
    val unstartedCellClass = classOf[UnstartedCell]
    val queueFieldName = Properties.versionNumberString.split("\\.").take(2).mkString(".") match {
      case _@ "2.11" ⇒ "akka$actor$UnstartedCell$$queue"
      case _@ "2.12" ⇒ "queue"
      case v         ⇒ throw new IllegalStateException(s"Incompatible Scala version: $v")
    }

    val queueField = unstartedCellClass.getDeclaredField(queueFieldName)
    queueField.setAccessible(true)

    val lockField = unstartedCellClass.getDeclaredField("lock")
    lockField.setAccessible(true)

    (queueField, lockField)
  }

}

trait ActorInstrumentationAware {
  def actorInstrumentation: ActorMonitor
  def setActorInstrumentation(ai: ActorMonitor): Unit
}

object ActorInstrumentationAware {
  def apply(): ActorInstrumentationAware = new ActorInstrumentationAware {
    private var _ai: ActorMonitor = _

    def setActorInstrumentation(ai: ActorMonitor): Unit = _ai = ai
    def actorInstrumentation: ActorMonitor = _ai
  }
}

@Aspect
class MetricsIntoActorCellsMixin {

  @DeclareMixin("akka.actor.ActorCell")
  def mixinActorCellMetricsToActorCell: ActorInstrumentationAware = ActorInstrumentationAware()

  @DeclareMixin("akka.actor.UnstartedCell")
  def mixinActorCellMetricsToUnstartedActorCell: ActorInstrumentationAware = ActorInstrumentationAware()

}
