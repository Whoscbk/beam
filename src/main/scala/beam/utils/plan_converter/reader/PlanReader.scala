package beam.utils.plan_converter.reader

import beam.utils.plan_converter.EntityTransformer
import beam.utils.plan_converter.entities.InputPlanElement

class PlanReader(path: String) extends BaseCsvReader[InputPlanElement](path) {
  override val transformer: EntityTransformer[InputPlanElement] = InputPlanElement
}
