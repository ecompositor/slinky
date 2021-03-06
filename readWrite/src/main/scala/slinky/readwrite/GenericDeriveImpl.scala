package slinky.readwrite

import scala.collection.mutable
import scala.reflect.macros.whitebox

abstract class GenericDeriveImpl(val c: whitebox.Context) {
  import c.universe._

  case class Param(name: Name, tpe: Type)

  val typeclassType: Type
  def deferredInstance(forType: Type, constantType: Type): Tree
  def maybeExtractDeferred(tree: Tree): Option[Tree]
  def createModuleTypeclass(tpe: Type, moduleReference: Tree): Tree
  def createCaseClassTypeclass(clazz: Type, params: Seq[Seq[Param]]): Tree
  def createValueClassTypeclass(clazz: Type, param: Param): Tree
  def createSealedTraitTypeclass(traitType: Type, subclasses: Seq[Symbol]): Tree
  def createFallback(forType: Type): Tree

  private lazy val typeclassSymbol = typeclassType.typeSymbol

  private def replaceDeferred(saveReferencesTo: mutable.Set[String]): Transformer = {
    new Transformer {
      override def transform(tree: Tree): Tree = maybeExtractDeferred(tree) match {
        case Some(t) =>
          val referenced = t.tpe.asInstanceOf[ConstantType].value.value.asInstanceOf[String]
          saveReferencesTo.add(referenced)
          q"${TermName(referenced)}"
        case None =>
          super.transform(tree)
      }
    }
  }

  def getTypeclass(forType: Type): Tree = {
    c.inferImplicitValue(appliedType(typeclassSymbol, forType))
  }

  private val currentMemo = {
    GenericDeriveImpl.derivationMemo.get().getOrElseUpdate(
      this.getClass.getSimpleName, mutable.Map.empty
    )
  }

  private val currentOrder = {
    GenericDeriveImpl.derivationOrder.get().getOrElseUpdate(
      this.getClass.getSimpleName, mutable.Queue.empty
    )
  }

  private def withMemoNone[T](tpe: Type)(thunk: => T): T = {
    val orig = currentMemo.get(tpe.toString)
    currentMemo(tpe.toString) = None
    try {
      thunk
    } finally {
      if (orig.isDefined) {
        currentMemo(tpe.toString) = orig.get
      } else {
        currentMemo.remove(tpe.toString)
      }
    }
  }

  private def memoTree[T](tpe: Type, symbol: Symbol)(tree: => Tree): Tree = {
    val fresh = c.freshName()
    currentMemo(tpe.toString) = Some(fresh)
    currentOrder.enqueue((fresh, tpe, tree))
    deferredInstance(
      tpe,
      c.internal.constantType(Constant(fresh))
    )
  }

  final def derive[T](implicit tTag: WeakTypeTag[T]): Tree = {
    val symbol = tTag.tpe.typeSymbol

    if (currentMemo.get(tTag.tpe.toString).contains(None)) {
      c.abort(c.enclosingPosition, "Skipping derivation macro when getting regular implicit")
    } else if (currentMemo.get(tTag.tpe.toString).flatten.isDefined) {
      deferredInstance(
        tTag.tpe,
        c.internal.constantType(Constant(currentMemo(tTag.tpe.toString).get))
      )
    } else if (symbol.isParameter) {
      c.abort(c.enclosingPosition, "")
    } else {
      val isRoot = currentMemo.isEmpty
      val regularImplicit = withMemoNone(tTag.tpe) {
        c.inferImplicitValue(
          appliedType(typeclassSymbol, tTag.tpe),
          silent = true
        )
      }

      val deriveTree = if (regularImplicit.isEmpty) {
        if (symbol.isModuleClass) {
          createModuleTypeclass(tTag.tpe, c.parse(symbol.asClass.module.fullName))
        } else if (symbol.isClass && symbol.asClass.isCaseClass) {
          val constructor = symbol.asClass.primaryConstructor
          val paramsLists = constructor.asMethod.paramLists
          memoTree(tTag.tpe, symbol) {
            val params: Seq[Seq[Param]] = paramsLists.map(_.map { p =>
              val transformedValueType = p.typeSignatureIn(tTag.tpe).resultType
              Param(p.name, transformedValueType.substituteTypes(symbol.asType.typeParams, tTag.tpe.typeArgs))
            })

            createCaseClassTypeclass(tTag.tpe, params)
          }
        } else if (symbol.isClass && tTag.tpe <:< typeOf[AnyVal]) {
          val actualValue = symbol.asClass.primaryConstructor.asMethod.paramLists.head.head
          val param = Param(actualValue.name, actualValue.typeSignatureIn(tTag.tpe).resultType)

          memoTree(tTag.tpe, symbol)(createValueClassTypeclass(tTag.tpe, param))
        } else if (symbol.isClass && symbol.asClass.isSealed && symbol.asType.toType.typeArgs.isEmpty) {
          def getSubclasses(clazz: ClassSymbol): Set[Symbol] = {
            // from magnolia
            val children = clazz.knownDirectSubclasses
            val (abstractTypes, concreteTypes) = children.partition(_.isAbstract)

            abstractTypes.map(_.asClass).flatMap(getSubclasses(_)) ++ concreteTypes
          }

          memoTree(tTag.tpe, symbol) {
            createSealedTraitTypeclass(tTag.tpe, getSubclasses(symbol.asClass).toSeq)
          }
        } else {
          memoTree(tTag.tpe, symbol) {
            c.echo(c.enclosingPosition, s"Using fallback derivation for type ${tTag.tpe} (derivation: ${getClass.getSimpleName})")
            createFallback(tTag.tpe)
          }
        }
      } else {
        if (isRoot) {
          regularImplicit
        } else {
          memoTree(tTag.tpe, symbol) {
            regularImplicit
          }
        }
      }

      if (isRoot) {
        val saveReferences = mutable.Set.empty[String]
        val unwrappedOrder = currentOrder.dequeueAll(_ => true).map { case (name, tpe, t) =>
          val typeclassTree = c.untypecheck(replaceDeferred(saveReferences).transform(t.asInstanceOf[Tree]))
          if (saveReferences.contains(name)) {
            (
              Some(q"var ${TermName(name)}: ${appliedType(typeclassSymbol, tpe.asInstanceOf[Type])} = null"),
              q"${TermName(name)} = $typeclassTree"
            )
          } else {
            (
              None,
              q"val ${TermName(name)}: ${appliedType(typeclassSymbol, tpe.asInstanceOf[Type])} = $typeclassTree"
            )
          }
        }

        currentMemo.clear()

        q"{ ..${unwrappedOrder.flatMap(_._1)}; ..${unwrappedOrder.map(_._2)}; ${replaceDeferred(saveReferences).transform(deriveTree)} }"
      } else {
        deriveTree
      }
    }
  }
}

object GenericDeriveImpl {
  private[GenericDeriveImpl] val derivationMemo = {
    new ThreadLocal[mutable.Map[String, mutable.Map[String, Option[String]]]] {
      override def initialValue() = mutable.Map.empty
    }
  }

  private[GenericDeriveImpl] val derivationOrder = {
    new ThreadLocal[mutable.Map[String, mutable.Queue[(String, whitebox.Context#Type, whitebox.Context#Tree)]]] {
      override def initialValue() = mutable.Map.empty
    }
  }
}
