package akka.kamon.instrumentation

import akka.stream.Attributes
import akka.stream.impl.fusing.GraphInterpreter
import akka.stream.stage.GraphStageLogic
import kamon.Kamon
import kamon.akka.StageMetrics
import kamon.metric.Entity
import kamon.util.RelativeNanoTimestamp
import org.aspectj.lang.annotation._

import scala.collection.mutable

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
            GraphLogicMonitor.createLogicMonitor(logic, s"${attributes.nameLifted.getOrElse(logic.interpreter.Name)}"))
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





    def createLogicMonitor(logic: GraphStageLogic, name: String): GraphLogicMonitor = {

        new GroupMetricsTrackingLogic(Entity(s"${name}", StageMetrics.category), Kamon.metrics.entity(StageMetrics, Entity(s"${name}", StageMetrics.category)))

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
