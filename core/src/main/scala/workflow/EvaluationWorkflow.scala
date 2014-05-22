package io.prediction.workflow

import scala.collection.mutable.ArrayBuffer
import io.prediction.AbstractEngine
import io.prediction.BaseTrainingDataParams
import io.prediction.BaseAlgoParams
import io.prediction.BaseServerParams
import io.prediction.BaseEvaluationParams
import io.prediction.AbstractEngine
import io.prediction.AbstractEvaluator
import io.prediction.AbstractEvaluationPreparator

class EvaluationWorkflow(val batch: String = "") {
  val tasks: ArrayBuffer[Task] = ArrayBuffer[Task]()
  var _lastId: Int = -1
  def nextId() : Int = { _lastId += 1; _lastId }

  def submit(task: Task): Int = {
    tasks.append(task)
    println(s"Task id: ${task.id} depends: ${task.dependingIds} task: $task")
    task.id
  }
}

object EvaluationWorkflow {
  def apply(
      batch: String,
      evalParams: BaseEvaluationParams,
      algoParamsList: Seq[(String, BaseAlgoParams)],
      serverParams: BaseServerParams,
      engine: AbstractEngine,
      evaluator: AbstractEvaluator,
      evaluationPreparator: AbstractEvaluationPreparator) {
    val workflow = new EvaluationWorkflow(batch)
    // In the comment, *_id corresponds to a task id.

    // eval to eval_params
    val evalParamsMap = evaluator.getParamsSetBase(evalParams)
      .zipWithIndex
      .map(_.swap)
      .toMap

    // algo to algo_params
    val algoParamsMap = algoParamsList.zipWithIndex.map(_.swap).toMap

    // Data Prep
    // eval to data_prep_id
    val dataPrepMap = evalParamsMap.map{ case(eval, evalParams) => {
      val (trainDataParams, evalDataParams) = evalParams
      val task = new DataPrepTask(workflow.nextId, batch,
        engine, trainDataParams)
      val dataPrepId = workflow.submit(task)
      (eval, dataPrepId)
    }}.toMap

    // Eval Prep
    // eval to eval_prep_id
    val evalPrepMap = evalParamsMap.map{ case(eval, evalParams) => {
      val (trainDataParams, evalDataParams) = evalParams
      val task = new EvalPrepTask(workflow.nextId, batch,
        evaluationPreparator, evalDataParams)
      val evalPrepId = workflow.submit(task)
      (eval, evalPrepId)
    }}.toMap

    // Training
    // (eval, algo) to training_id
    val modelMap = dataPrepMap.map{ case(eval, dataPrepId) => {
      algoParamsMap.map{ case(algo, (algoName, algoParams)) => {
        val task = new TrainingTask(workflow.nextId, batch, 
          engine, algoName, algoParams, dataPrepId)
        val trainingId = workflow.submit(task)
        ((eval, algo) , trainingId)
      }}
    }}.flatten.toMap

    // Prediction Task
    // (eval, algo) to predict_id
    val predictionMap = modelMap.map{ case((eval, algo), trainingId) => {
      val (algoName, algoParams) = algoParamsMap(algo)
      val evalPrepId = evalPrepMap(eval)
      val task = new PredictionTask(workflow.nextId, batch, engine, algoName,
        algoParams, trainingId, evalPrepId)
      val predictionId = workflow.submit(task)
      ((eval, algo), predictionId)
    }}.toMap

    // Server Task
    // eval to server_id, (prediction task group by eval)
    val algoList = algoParamsMap.keys.toSeq
    val serverMap = evalParamsMap.map{ case(eval, _) => {
      val predictionIds = algoList.map(algo => predictionMap((eval, algo)))
      val task = new ServerTask(workflow.nextId, batch, engine, serverParams,
        predictionIds)
      val serverId = workflow.submit(task)
      (eval, serverId)
    }}.toMap

    // EvaluationUnitTask
    // eval to eval_unit_id
    val evalUnitMap = serverMap.map{ case(eval, serverId) => {
      val task = new EvaluationUnitTask(workflow.nextId, batch, evaluator,
        serverId, evalPrepMap(eval))
      val evalUnitId = workflow.submit(task)
      (eval, evalUnitId)
    }}

    // EvaluationReportTask
    // eval to eval_report_id
    val evalReportMap = evalUnitMap.map{ case(eval, evalUnitId) => {
      val task = new EvaluationReportTask(workflow.nextId, batch, evaluator,
        evalUnitId)
      val evalReportId = workflow.submit(task)
      (eval, evalReportId)
    }}
  }
}
