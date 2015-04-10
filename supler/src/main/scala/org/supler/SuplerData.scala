package org.supler

import org.json4s.JValue
import org.json4s.JsonAST._
import org.supler.field._
import org.supler.validation._

/**
 * At any stage of processing data with Supler, the result can be serialized to JSON and sent to the server.
 * The data can be either:
 * - form with an object, and optional errors and custom data
 * - custom data only, represented as json. This can be a result of running an action.
 */
sealed trait SuplerData {
  def generateJSON: JValue
}

trait FormWithObject[T] extends SuplerData {
  def obj: T

  def form: Form[T]

  def meta: FormMeta

  /**
   * Custom data which will be included in the generated JSON, passed to the frontend.
   * Not used or manipulated in any other way by Supler.
   */
  def customData: Option[JValue]

  protected def applyErrors: FieldErrors

  protected def validationErrors: FieldErrors

  protected def allErrors: FieldErrors = applyErrors ++ validationErrors

  def withMeta(key: String, value: String): FormWithObject[T]

  def applyJSONValues(jvalue: JValue): JSONApplyResult[T] = {
    modalFormPath(jvalue) match {
      case Some(path) => form.findFieldByPath(path) match {
        case Some(modal: ModalField[T, _]) => {
          applyJSONValuesOnModal(modal, jvalue)
        }
        case other =>
          throw new IllegalStateException(s"Expected field of type ModalField under path ${path} but got ${other}")
      }
      case None => {
        val result = form.applyJSONValues(EmptyPath, obj, jvalue)
        ParentFormApplyResult(
          new FormWithObjectAndErrors(form, result.obj, customData, result.errors, Nil, FormMeta.fromJSON(jvalue)))
      }
    }
  }

  private def applyJSONValuesOnModal[U](modal: ModalField[T, U], jvalue: JValue) : ModalFormApplyResult[T] = {
    val blah = modal.form(obj)
    val modalForm: FormWithObject[U] = modal.form(obj)
    val modalObj = modalForm.obj
    val result = modalForm.form.applyJSONValues(EmptyPath, modalObj, jvalue)
    ModalFormApplyResult(new FormWithObjectAndErrors[U](modalForm.form, result.obj, modalForm.customData, 
      result.errors, Nil, FormMeta.fromJSON(jvalue)))
  }

  private def modalFormPath(jvalue: JValue): Option[String] = {
    jvalue match {
      case JObject (jsonFields) =>
        jsonFields.toMap.get ("supler_modal_path") match {
          case Some(JString(path)) => Some(path)
          case _ => None
        }
      case _ => None
    }
  }

  def doValidate(scope: ValidationScope = ValidateAll): FormWithObjectAndErrors[T] = {
    val currentApplyErrors = applyErrors
    val newValidationErrors = form.doValidate(EmptyPath, obj, scope)

    new FormWithObjectAndErrors(form, obj, customData, currentApplyErrors, newValidationErrors, meta)
  }

  override def generateJSON: JValue = {
    JObject(
      meta.toJSON,
      JField("is_supler_form", JBool(value = true)),
      JField("main_form", form.generateJSON(EmptyPath, obj)),
      JField("errors", JArray(allErrors.map(_.generateJSON))),
      JField("custom_data", customData.getOrElse(JNothing))
    )
  }

  /**
   * Processes a form basing on new field values and an optional action call, preparing a result that can be sent
   * back to the frontend.
   *
   * If there's no action, validation of filled fields is run and the result is returned.
   * If there's an action, the action result (optional) together with the new form (optional) is returned.
   */
  def process(jvalue: JValue): SuplerData = {
    val applied = this.applyJSONValues(jvalue).formObjectAndErrors

    applied.findAndRunAction(jvalue) match {
      case Some(actionResult) => actionResult
      case None => applied.findAndShowModal(jvalue) match {
        case Some(showableModal) => ModalFormWithObject[T](showableModal.path, showableModal.form)
        case None => applied.doValidate(ValidateFilled)
      }
    }
  }

  /**
   * Finds an action, and if there's one, runs the required validation and if there are no errors, the action itself.
   * @return `Some` if an action was found. Contains the validated form with errors or the action result.
   */
  def findAndRunAction(jvalue: JValue): Option[SuplerData] = {
    form.findAction(EmptyPath, obj, jvalue, RunActionContext(Nil)).map { runnableAction =>
      val validated = this.doValidate(runnableAction.validationScope)
      if (validated.hasErrors) {
        validated
      } else {
        runnableAction.run() match {
          case FullCompleteActionResult(t, customData) => InitialFormWithObject(form, t.asInstanceOf[T], customData, meta)
          case CustomDataCompleteActionResult(json) => CustomDataOnly(json)
        }
      }
    }
  }

  def findAndShowModal(jvalue: JValue): Option[ShowableModal] = {
    form.findModal(EmptyPath, obj, jvalue)
  }
}

case class InitialFormWithObject[T](form: Form[T], obj: T, customData: Option[JValue], meta: FormMeta)
  extends FormWithObject[T] {
  override protected val applyErrors = Nil
  override protected val validationErrors = Nil

  override def withMeta(key: String, value: String): InitialFormWithObject[T] = this.copy(meta = meta +(key, value))
}

case class FormWithObjectAndErrors[T](
                                       form: Form[T],
                                       obj: T,
                                       customData: Option[JValue],
                                       applyErrors: FieldErrors,
                                       validationErrors: FieldErrors,
                                       meta: FormMeta) extends FormWithObject[T] {

  def errors: FieldErrors = allErrors

  def hasErrors: Boolean = allErrors.size > 0

  override def withMeta(key: String, value: String): FormWithObjectAndErrors[T] = this.copy(meta = meta +(key, value))
}

case class CustomDataOnly private[supler](customData: JValue) extends SuplerData {
  override def generateJSON = customData
}

case class ModalFormWithObject[T](modalPath: FieldPath, modalForm: SuplerData) extends SuplerData {
  override def generateJSON = JObject(
    JField("type", JString("modal")),
    JField("path", JString(modalPath.toString)),
    JField("form", modalForm.generateJSON)
  )
}

sealed trait JSONApplyResult[T] {
  def formObjectAndErrors: FormWithObjectAndErrors[_]
}

case class ParentFormApplyResult[T](formObjectAndErrors: FormWithObjectAndErrors[T]) extends JSONApplyResult[T]

case class ModalFormApplyResult[T](formObjectAndErrors: FormWithObjectAndErrors[_]) extends JSONApplyResult[T]