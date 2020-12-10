package scala.quoted
package runtime.impl

import dotty.tools.dotc
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Annotations
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Types
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.NameKinds
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.quoted.reflect._
import dotty.tools.dotc.quoted.QuoteUtils._
import dotty.tools.dotc.core.Decorators._

import dotty.tools.dotc.quoted.{MacroExpansion, PickledQuotes, QuoteUtils}

import scala.quoted.runtime.{QuoteUnpickler, QuoteMatching}
import scala.quoted.runtime.impl.printers._

import scala.reflect.TypeTest

object QuotesImpl {

  type ScopeId = Int

  def apply()(using Context): Quotes =
    new QuotesImpl

  def showDecompiledTree(tree: tpd.Tree)(using Context): String =
    import qctx.reflect.Printer.{TreeCode, TreeAnsiCode}
    val qctx: QuotesImpl = new QuotesImpl(using MacroExpansion.context(tree))
    if ctx.settings.color.value == "always" then TreeAnsiCode.show(tree)
    else TreeCode.show(tree)

  // TODO Explore more fine grained scope ids.
  //      This id can only differentiate scope extrusion from one compiler instance to another.
  def scopeId(using Context): ScopeId =
    ctx.outersIterator.toList.last.hashCode()

}

class QuotesImpl private (using val ctx: Context) extends Quotes, QuoteUnpickler, QuoteMatching:

  private val yCheck: Boolean =
    ctx.settings.Ycheck.value(using ctx).exists(x => x == "all" || x == "macros")

  extension [T](self: scala.quoted.Expr[T])
    def show: String =
      reflect.Printer.TreeCode.show(reflect.asTerm(self))

    def matches(that: scala.quoted.Expr[Any]): Boolean =
      treeMatch(reflect.asTerm(self), reflect.asTerm(that)).nonEmpty

  end extension

  extension [X](self: scala.quoted.Expr[Any])
    /** Checks is the `quoted.Expr[?]` is valid expression of type `X` */
    def isExprOf(using scala.quoted.Type[X]): Boolean =
      reflect.TypeReprMethods.<:<(reflect.asTerm(self).tpe)(reflect.TypeRepr.of[X])

    /** Convert this to an `quoted.Expr[X]` if this expression is a valid expression of type `X` or throws */
    def asExprOf(using scala.quoted.Type[X]): scala.quoted.Expr[X] = {
      if isExprOf[X] then
        self.asInstanceOf[scala.quoted.Expr[X]]
      else
        throw Exception(
          s"""Expr cast exception: ${self.show}
            |of type: ${reflect.Printer.TypeReprCode.show(reflect.asTerm(self).tpe)}
            |did not conform to type: ${reflect.Printer.TypeReprCode.show(reflect.TypeRepr.of[X])}
            |""".stripMargin
        )
    }
  end extension

  object reflect extends reflectModule:

    extension (expr: Expr[Any])
      def asTerm: Term =
        val exprImpl = expr.asInstanceOf[ExprImpl]
        exprImpl.checkScopeId(QuotesImpl.this.hashCode)
        exprImpl.tree
    end extension

    type Tree = tpd.Tree

    object Tree extends TreeModule

    given TreeMethods: TreeMethods with
      extension (self: Tree)
        def pos: Position = self.sourcePos
        def symbol: Symbol = self.symbol
        def show(using printer: Printer[Tree]): String = printer.show(self)
        def isExpr: Boolean =
          self match
            case TermTypeTest(self) =>
              self.tpe.widen match
                case _: MethodType | _: PolyType => false
                case _ => true
            case _ => false
        def asExpr: scala.quoted.Expr[Any] =
          if self.isExpr then
            new ExprImpl(self, QuotesImpl.this.hashCode)
          else self match
            case TermTypeTest(self) => throw new Exception("Expected an expression. This is a partially applied Term. Try eta-expanding the term first.")
            case _ => throw new Exception("Expected a Term but was: " + self)
      end extension

      extension [T](self: Tree)
        def asExprOf(using tp: scala.quoted.Type[T]): scala.quoted.Expr[T] =
          QuotesImpl.this.asExprOf[T](self.asExpr)(using tp)
      end extension

      extension [ThisTree <: Tree](self: ThisTree)
        def changeOwner(newOwner: Symbol): ThisTree =
          tpd.TreeOps(self).changeNonLocalOwners(newOwner).asInstanceOf[ThisTree]
      end extension

    end TreeMethods

    type PackageClause = tpd.PackageDef

    object PackageClauseTypeTest extends TypeTest[Tree, PackageClause]:
      def unapply(x: Tree): Option[PackageClause & x.type] = x match
        case x: (tpd.PackageDef & x.type) => Some(x)
        case _ => None
    end PackageClauseTypeTest

    object PackageClause extends PackageClauseModule:
      def apply(pid: Ref, stats: List[Tree]): PackageClause =
        withDefaultPos(tpd.PackageDef(pid.asInstanceOf[tpd.RefTree], stats))
      def copy(original: Tree)(pid: Ref, stats: List[Tree]): PackageClause =
        tpd.cpy.PackageDef(original)(pid, stats)
      def unapply(tree: PackageClause): (Ref, List[Tree]) =
        (tree.pid, tree.stats)
    end PackageClause

    given PackageClauseMethods: PackageClauseMethods with
      extension (self: PackageClause)
        def pid: Ref = self.pid
        def stats: List[Tree] = self.stats
      end extension
    end PackageClauseMethods

    type Import = tpd.Import

    object ImportTypeTest extends TypeTest[Tree, Import]:
      def unapply(x: Tree): Option[Import & x.type] = x match
        case tree: (tpd.Import & x.type) => Some(tree)
        case _ => None
    end ImportTypeTest

    object Import extends ImportModule:
      def apply(expr: Term, selectors: List[Selector]): Import =
        withDefaultPos(tpd.Import(expr, selectors))
      def copy(original: Tree)(expr: Term, selectors: List[Selector]): Import =
        tpd.cpy.Import(original)(expr, selectors)
      def unapply(tree: Import): (Term, List[Selector]) =
        (tree.expr, tree.selectors)
    end Import

    given ImportMethods: ImportMethods with
      extension (self: Import)
        def expr: Term = self.expr
        def selectors: List[Selector] = self.selectors
      end extension
    end ImportMethods

    type Export = tpd.Export

    object ExportTypeTest extends TypeTest[Tree, Export]:
      def unapply(x: Tree): Option[Export & x.type] = x match
        case tree: (tpd.Export & x.type) => Some(tree)
        case _ => None
    end ExportTypeTest

    object Export extends ExportModule:
      def unapply(tree: Export): (Term, List[Selector]) =
        (tree.expr, tree.selectors)
    end Export

    given ExportMethods: ExportMethods with
      extension (self: Export)
        def expr: Term = self.expr
        def selectors: List[Selector] = self.selectors
      end extension
    end ExportMethods

    type Statement = tpd.Tree

    object StatementTypeTest extends TypeTest[Tree, Statement]:
      def unapply(x: Tree): Option[Statement & x.type] = x match
        case _: tpd.PatternTree => None
        case _ =>
          if x.isTerm then TermTypeTest.unapply(x)
          else DefinitionTypeTest.unapply(x)
    end StatementTypeTest

    type Definition = tpd.MemberDef

    object DefinitionTypeTest extends TypeTest[Tree, Definition]:
      def unapply(x: Tree): Option[Definition & x.type] = x match
        case x: (tpd.MemberDef & x.type) => Some(x)
        case _ => None
    end DefinitionTypeTest

    object Definition extends DefinitionModule

    given DefinitionMethods: DefinitionMethods with
      extension (self: Definition)
        def name: String = self match
          case self: tpd.MemberDef => self.name.toString
      end extension
    end DefinitionMethods

    type ClassDef = tpd.TypeDef

    object ClassDefTypeTest extends TypeTest[Tree, ClassDef]:
      def unapply(x: Tree): Option[ClassDef & x.type] = x match
        case x: (tpd.TypeDef & x.type) if x.isClassDef => Some(x)
        case _ => None
    end ClassDefTypeTest

    object ClassDef extends ClassDefModule:
      def copy(original: Tree)(name: String, constr: DefDef, parents: List[Tree], derived: List[TypeTree], selfOpt: Option[ValDef], body: List[Statement]): ClassDef = {
        val dotc.ast.Trees.TypeDef(_, originalImpl: tpd.Template) = original
        tpd.cpy.TypeDef(original)(name.toTypeName, tpd.cpy.Template(originalImpl)(constr, parents, derived, selfOpt.getOrElse(tpd.EmptyValDef), body))
      }
      def unapply(cdef: ClassDef): (String, DefDef, List[Tree /* Term | TypeTree */], List[TypeTree], Option[ValDef], List[Statement]) =
        val rhs = cdef.rhs.asInstanceOf[tpd.Template]
        (cdef.name.toString, cdef.constructor, cdef.parents, rhs.derived.asInstanceOf[List[TypeTree]], cdef.self, rhs.body)
    end ClassDef

    given ClassDefMethods: ClassDefMethods with
      extension (self: ClassDef)
        def constructor: DefDef =
          self.rhs.asInstanceOf[tpd.Template].constr
        def parents: List[Tree] =
          self.rhs.asInstanceOf[tpd.Template].parents
        def derived: List[TypeTree] =
          self.rhs.asInstanceOf[tpd.Template].derived.asInstanceOf[List[TypeTree]]
        def self: Option[ValDef] =
          optional(self.rhs.asInstanceOf[tpd.Template].self)
        def body: List[Statement] =
          self.rhs.asInstanceOf[tpd.Template].body
      end extension
    end ClassDefMethods

    type DefDef = tpd.DefDef

    object DefDefTypeTest extends TypeTest[Tree, DefDef]:
      def unapply(x: Tree): Option[DefDef & x.type] = x match
        case x: (tpd.DefDef & x.type) => Some(x)
        case _ => None
    end DefDefTypeTest

    object DefDef extends DefDefModule:
      def apply(symbol: Symbol, rhsFn: List[TypeRepr] => List[List[Term]] => Option[Term]): DefDef =
        withDefaultPos(tpd.polyDefDef(symbol.asTerm, tparams => vparamss => yCheckedOwners(rhsFn(tparams)(vparamss), symbol).getOrElse(tpd.EmptyTree)))
      def copy(original: Tree)(name: String, typeParams: List[TypeDef], paramss: List[List[ValDef]], tpt: TypeTree, rhs: Option[Term]): DefDef =
        tpd.cpy.DefDef(original)(name.toTermName, typeParams, paramss, tpt, yCheckedOwners(rhs, original.symbol).getOrElse(tpd.EmptyTree))
      def unapply(ddef: DefDef): (String, List[TypeDef], List[List[ValDef]], TypeTree, Option[Term]) =
        (ddef.name.toString, ddef.typeParams, ddef.paramss, ddef.tpt, optional(ddef.rhs))
    end DefDef

    given DefDefMethods: DefDefMethods with
      extension (self: DefDef)
        def typeParams: List[TypeDef] = self.tparams
        def paramss: List[List[ValDef]] = self.vparamss
        def returnTpt: TypeTree = self.tpt
        def rhs: Option[Term] = optional(self.rhs)
      end extension
    end DefDefMethods

    type ValDef = tpd.ValDef

    object ValDefTypeTest extends TypeTest[Tree, ValDef]:
      def unapply(x: Tree): Option[ValDef & x.type] = x match
        case x: (tpd.ValDef & x.type) => Some(x)
        case _ => None
    end ValDefTypeTest

    object ValDef extends ValDefModule:
      def apply(symbol: Symbol, rhs: Option[Term]): ValDef =
        tpd.ValDef(symbol.asTerm, yCheckedOwners(rhs, symbol).getOrElse(tpd.EmptyTree))
      def copy(original: Tree)(name: String, tpt: TypeTree, rhs: Option[Term]): ValDef =
        tpd.cpy.ValDef(original)(name.toTermName, tpt, yCheckedOwners(rhs, original.symbol).getOrElse(tpd.EmptyTree))
      def unapply(vdef: ValDef): (String, TypeTree, Option[Term]) =
        (vdef.name.toString, vdef.tpt, optional(vdef.rhs))

      def let(owner: Symbol, name: String, rhs: Term)(body: Ident => Term): Term =
        val vdef = tpd.SyntheticValDef(name.toTermName, rhs)(using ctx.withOwner(owner))
        val ref = tpd.ref(vdef.symbol).asInstanceOf[Ident]
        Block(List(vdef), body(ref))

      def let(owner: Symbol, terms: List[Term])(body: List[Ident] => Term): Term =
        val ctx1 = ctx.withOwner(owner)
        val vdefs = terms.map(term => tpd.SyntheticValDef("x".toTermName, term)(using ctx1))
        val refs = vdefs.map(vdef => tpd.ref(vdef.symbol).asInstanceOf[Ident])
        Block(vdefs, body(refs))
    end ValDef

    given ValDefMethods: ValDefMethods with
      extension (self: ValDef)
        def tpt: TypeTree = self.tpt
        def rhs: Option[Term] = optional(self.rhs)
      end extension
    end ValDefMethods

    type TypeDef = tpd.TypeDef

    object TypeDefTypeTest extends TypeTest[Tree, TypeDef]:
      def unapply(x: Tree): Option[TypeDef & x.type] = x match
        case x: (tpd.TypeDef & x.type) if !x.isClassDef => Some(x)
        case _ => None
    end TypeDefTypeTest

    object TypeDef extends TypeDefModule:
      def apply(symbol: Symbol): TypeDef =
        withDefaultPos(tpd.TypeDef(symbol.asType))
      def copy(original: Tree)(name: String, rhs: Tree): TypeDef =
        tpd.cpy.TypeDef(original)(name.toTypeName, rhs)
      def unapply(tdef: TypeDef): (String, Tree /*TypeTree | TypeBoundsTree*/ /* TypeTree | TypeBoundsTree */) =
        (tdef.name.toString, tdef.rhs)
    end TypeDef

    given TypeDefMethods: TypeDefMethods with
      extension (self: TypeDef)
        def rhs: Tree = self.rhs
      end extension
    end TypeDefMethods

    type Term = tpd.Tree

    object TermTypeTest extends TypeTest[Tree, Term]:
      def unapply(x: Tree): Option[Term & x.type] = x match
        case _ if UnapplyTypeTest.unapply(x).isDefined => None
        case _: tpd.PatternTree => None
        case x: (tpd.Tree & x.type) if x.isTerm => Some(x)
        case x: (tpd.SeqLiteral & x.type) => Some(x)
        case x: (tpd.Inlined & x.type) => Some(x)
        case x: (tpd.NamedArg & x.type) => Some(x)
        case _ => None
    end TermTypeTest

    object Term extends TermModule:
      def betaReduce(tree: Term): Option[Term] =
        tree match
          case app @ tpd.Apply(tpd.Select(fn, nme.apply), args) if dotc.core.Symbols.defn.isFunctionType(fn.tpe) =>
            val app1 = dotc.transform.BetaReduce(app, fn, args)
            if app1 eq app then None
            else Some(app1.withSpan(tree.span))
          case tpd.Block(Nil, expr) =>
            for e <- betaReduce(expr) yield tpd.cpy.Block(tree)(Nil, e)
          case tpd.Inlined(_, Nil, expr) =>
            betaReduce(expr)
          case _ =>
            None
    end Term

    given TermMethods: TermMethods with
      extension (self: Term)
        def seal: scala.quoted.Expr[Any] =
          if self.isExpr then new ExprImpl(self, QuotesImpl.this.hashCode)
          else throw new Exception("Cannot seal a partially applied Term. Try eta-expanding the term first.")

        def sealOpt: Option[scala.quoted.Expr[Any]] =
          if self.isExpr then Some(new ExprImpl(self, QuotesImpl.this.hashCode))
          else None

        def tpe: TypeRepr = self.tpe
        def underlyingArgument: Term = new tpd.TreeOps(self).underlyingArgument
        def underlying: Term = new tpd.TreeOps(self).underlying
        def etaExpand(owner: Symbol): Term = self.tpe.widen match {
          case mtpe: Types.MethodType if !mtpe.isParamDependent =>
            val closureResType = mtpe.resType match {
              case t: Types.MethodType => t.toFunctionType()
              case t => t
            }
            val closureTpe = Types.MethodType(mtpe.paramNames, mtpe.paramInfos, closureResType)
            val closureMethod = dotc.core.Symbols.newSymbol(owner, nme.ANON_FUN, Synthetic | Method, closureTpe)
            tpd.Closure(closureMethod, tss => new tpd.TreeOps(self).appliedToArgs(tss.head).etaExpand(closureMethod))
          case _ => self
        }

        def appliedTo(arg: Term): Term =
          self.appliedToArgs(arg :: Nil)
        def appliedTo(arg: Term, args: Term*): Term =
          self.appliedToArgs(arg :: args.toList)
        def appliedToArgs(args: List[Term]): Apply =
          Apply(self, args)
        def appliedToArgss(argss: List[List[Term]]): Term =
          argss.foldLeft(self: Term)(Apply(_, _))
        def appliedToNone: Apply =
          self.appliedToArgs(Nil)
        def appliedToType(targ: TypeRepr): Term =
          self.appliedToTypes(targ :: Nil)
        def appliedToTypes(targs: List[TypeRepr]): Term =
          self.appliedToTypeTrees(targs map (Inferred(_)))
        def appliedToTypeTrees(targs: List[TypeTree]): Term =
          if (targs.isEmpty) self else TypeApply(self, targs)
        def select(sym: Symbol): Select = Select(self, sym)

      end extension
    end TermMethods

    type Ref = tpd.RefTree

    object RefTypeTest extends TypeTest[Tree, Ref]:
      def unapply(x: Tree): Option[Ref & x.type] = x match
        case x: (tpd.RefTree & x.type) if x.isTerm => Some(x)
        case _ => None
    end RefTypeTest

    object Ref extends RefModule:
      def term(tp: TermRef): Ref =
        withDefaultPos(tpd.ref(tp).asInstanceOf[tpd.RefTree])
      def apply(sym: Symbol): Ref =
        assert(sym.isTerm)
        withDefaultPos(tpd.ref(sym).asInstanceOf[tpd.RefTree])
    end Ref

    type Ident = tpd.Ident

    object IdentTypeTest extends TypeTest[Tree, Ident]:
      def unapply(x: Tree): Option[Ident & x.type] = x match
        case x: (tpd.Ident & x.type) if x.isTerm => Some(x)
        case _ => None
    end IdentTypeTest

    object Ident extends IdentModule:
      def apply(tmref: TermRef): Term =
        withDefaultPos(tpd.ref(tmref).asInstanceOf[Term])
      def copy(original: Tree)(name: String): Ident =
        tpd.cpy.Ident(original)(name.toTermName)
      def unapply(tree: Ident): Some[String] =
        Some(tree.name.toString)
    end Ident

    given IdentMethods: IdentMethods with
      extension (self: Ident)
        def name: String = self.name.toString
      end extension
    end IdentMethods

    type Select = tpd.Select

    object SelectTypeTest extends TypeTest[Tree, Select]:
      def unapply(x: Tree): Option[Select & x.type] = x match
        case x: (tpd.Select & x.type) if x.isTerm => Some(x)
        case _ => None
    end SelectTypeTest

    object Select extends SelectModule:
      def apply(qualifier: Term, symbol: Symbol): Select =
        withDefaultPos(tpd.Select(qualifier, Types.TermRef(qualifier.tpe, symbol)))
      def unique(qualifier: Term, name: String): Select =
        val denot = qualifier.tpe.member(name.toTermName)
        assert(!denot.isOverloaded, s"The symbol `$name` is overloaded. The method Select.unique can only be used for non-overloaded symbols.")
        withDefaultPos(tpd.Select(qualifier, name.toTermName))
      def overloaded(qualifier: Term, name: String, targs: List[TypeRepr], args: List[Term]): Apply =
        withDefaultPos(tpd.applyOverloaded(qualifier, name.toTermName, args, targs, Types.WildcardType).asInstanceOf[Apply])

      def overloaded(qualifier: Term, name: String, targs: List[TypeRepr], args: List[Term], returnType: TypeRepr): Apply =
        withDefaultPos(tpd.applyOverloaded(qualifier, name.toTermName, args, targs, returnType).asInstanceOf[Apply])
      def copy(original: Tree)(qualifier: Term, name: String): Select =
        tpd.cpy.Select(original)(qualifier, name.toTermName)
      def unapply(x: Select): (Term, String) =
        (x.qualifier, x.name.toString)
    end Select

    given SelectMethods: SelectMethods with
      extension (self: Select)
        def qualifier: Term = self.qualifier
        def name: String = self.name.toString
        def signature: Option[Signature] =
          if self.symbol.signature == dotc.core.Signature.NotAMethod then None
          else Some(self.symbol.signature)
      end extension
    end SelectMethods

    type Literal = tpd.Literal

    object LiteralTypeTest extends TypeTest[Tree, Literal]:
      def unapply(x: Tree): Option[Literal & x.type] = x match
        case x: (tpd.Literal & x.type) => Some(x)
        case _ => None
    end LiteralTypeTest

    object Literal extends LiteralModule:
      def apply(constant: Constant): Literal =
        withDefaultPos(tpd.Literal(constant))
      def copy(original: Tree)(constant: Constant): Literal =
        tpd.cpy.Literal(original)(constant)
      def unapply(x: Literal): Some[Constant] =
        Some(x.constant)
    end Literal

    given LiteralMethods: LiteralMethods with
      extension (self: Literal)
        def constant: Constant = self.const
      end extension
    end LiteralMethods

    type This = tpd.This

    object ThisTypeTest extends TypeTest[Tree, This]:
      def unapply(x: Tree): Option[This & x.type] = x match
        case x: (tpd.This & x.type) => Some(x)
        case _ => None
    end ThisTypeTest

    object This extends ThisModule:
      def apply(cls: Symbol): This =
        withDefaultPos(tpd.This(cls.asClass))
      def copy(original: Tree)(qual: Option[String]): This =
        tpd.cpy.This(original)(qual.map(x => untpd.Ident(x.toTypeName)).getOrElse(untpd.EmptyTypeIdent))
      def unapply(x: This): Some[Option[String]] =
        Some(optional(x.qual).map(_.name.toString))
    end This

    given ThisMethods: ThisMethods with
      extension (self: This)
        def id: Option[String] = optional(self.qual).map(_.name.toString)
      end extension
    end ThisMethods

    type New = tpd.New

    object NewTypeTest extends TypeTest[Tree, New]:
      def unapply(x: Tree): Option[New & x.type] = x match
        case x: (tpd.New & x.type) => Some(x)
        case _ => None
    end NewTypeTest

    object New extends NewModule:
      def apply(tpt: TypeTree): New =
        withDefaultPos(tpd.New(tpt))
      def copy(original: Tree)(tpt: TypeTree): New =
        tpd.cpy.New(original)(tpt)
      def unapply(x: New): Some[TypeTree] = Some(x.tpt)
    end New

    given NewMethods: NewMethods with
      extension (self: New)
        def tpt: TypeTree = self.tpt
      end extension
    end NewMethods

    type NamedArg = tpd.NamedArg

    object NamedArgTypeTest extends TypeTest[Tree, NamedArg]:
      def unapply(x: Tree): Option[NamedArg & x.type] = x match
        case x: (tpd.NamedArg & x.type) if x.name.isInstanceOf[dotc.core.Names.TermName] => Some(x) // TODO: Now, the name should alwas be a term name
        case _ => None
    end NamedArgTypeTest

    object NamedArg extends NamedArgModule:
      def apply(name: String, arg: Term): NamedArg =
        withDefaultPos(tpd.NamedArg(name.toTermName, arg))
      def copy(original: Tree)(name: String, arg: Term): NamedArg =
        tpd.cpy.NamedArg(original)(name.toTermName, arg)
      def unapply(x: NamedArg): (String, Term) =
        (x.name.toString, x.value)
    end NamedArg

    given NamedArgMethods: NamedArgMethods with
      extension (self: NamedArg)
        def name: String = self.name.toString
        def value: Term = self.arg
      end extension
    end NamedArgMethods

    type Apply = tpd.Apply

    object ApplyTypeTest extends TypeTest[Tree, Apply]:
      def unapply(x: Tree): Option[Apply & x.type] = x match
        case x: (tpd.Apply & x.type) => Some(x)
        case _ => None
    end ApplyTypeTest

    object Apply extends ApplyModule:
      def apply(fun: Term, args: List[Term]): Apply =
        withDefaultPos(tpd.Apply(fun, args))
      def copy(original: Tree)(fun: Term, args: List[Term]): Apply =
        tpd.cpy.Apply(original)(fun, args)
      def unapply(x: Apply): (Term, List[Term]) =
        (x.fun, x.args)
    end Apply

    given ApplyMethods: ApplyMethods with
      extension (self: Apply)
        def fun: Term = self.fun
        def args: List[Term] = self.args
      end extension
    end ApplyMethods

    type TypeApply = tpd.TypeApply

    object TypeApplyTypeTest extends TypeTest[Tree, TypeApply]:
      def unapply(x: Tree): Option[TypeApply & x.type] = x match
        case x: (tpd.TypeApply & x.type) => Some(x)
        case _ => None
    end TypeApplyTypeTest

    object TypeApply extends TypeApplyModule:
      def apply(fun: Term, args: List[TypeTree]): TypeApply =
        withDefaultPos(tpd.TypeApply(fun, args))
      def copy(original: Tree)(fun: Term, args: List[TypeTree]): TypeApply =
        tpd.cpy.TypeApply(original)(fun, args)
      def unapply(x: TypeApply): (Term, List[TypeTree]) =
        (x.fun, x.args)
    end TypeApply

    given TypeApplyMethods: TypeApplyMethods with
      extension (self: TypeApply)
        def fun: Term = self.fun
        def args: List[TypeTree] = self.args
      end extension
    end TypeApplyMethods

    type Super = tpd.Super

    object SuperTypeTest extends TypeTest[Tree, Super]:
      def unapply(x: Tree): Option[Super & x.type] = x match
        case x: (tpd.Super & x.type) => Some(x)
        case _ => None
    end SuperTypeTest

    object Super extends SuperModule:
      def apply(qual: Term, mix: Option[String]): Super =
        withDefaultPos(tpd.Super(qual, mix.map(x => untpd.Ident(x.toTypeName)).getOrElse(untpd.EmptyTypeIdent), dotc.core.Symbols.NoSymbol))
      def copy(original: Tree)(qual: Term, mix: Option[String]): Super =
        tpd.cpy.Super(original)(qual, mix.map(x => untpd.Ident(x.toTypeName)).getOrElse(untpd.EmptyTypeIdent))
      def unapply(x: Super): (Term, Option[String]) =
        (x.qualifier, x.id)
    end Super

    given SuperMethods: SuperMethods with
      extension (self: Super)
        def qualifier: Term = self.qual
        def id: Option[String] = optional(self.mix).map(_.name.toString)
        def idPos: Position = self.mix.sourcePos
      end extension
    end SuperMethods

    type Typed = tpd.Typed

    object TypedTypeTest extends TypeTest[Tree, Typed]:
      def unapply(x: Tree): Option[Typed & x.type] = x match
        case x: (tpd.Typed & x.type) => Some(x)
        case _ => None
    end TypedTypeTest

    object Typed extends TypedModule:
      def apply(expr: Term, tpt: TypeTree): Typed =
        withDefaultPos(tpd.Typed(expr, tpt))
      def copy(original: Tree)(expr: Term, tpt: TypeTree): Typed =
        tpd.cpy.Typed(original)(expr, tpt)
      def unapply(x: Typed): (Term, TypeTree) =
        (x.expr, x.tpt)
    end Typed

    given TypedMethods: TypedMethods with
      extension (self: Typed)
        def expr: Term = self.expr
        def tpt: TypeTree = self.tpt
      end extension
    end TypedMethods

    type Assign = tpd.Assign

    object AssignTypeTest extends TypeTest[Tree, Assign]:
      def unapply(x: Tree): Option[Assign & x.type] = x match
        case x: (tpd.Assign & x.type) => Some(x)
        case _ => None
    end AssignTypeTest

    object Assign extends AssignModule:
      def apply(lhs: Term, rhs: Term): Assign =
        withDefaultPos(tpd.Assign(lhs, rhs))
      def copy(original: Tree)(lhs: Term, rhs: Term): Assign =
        tpd.cpy.Assign(original)(lhs, rhs)
      def unapply(x: Assign): (Term, Term) =
        (x.lhs, x.rhs)
    end Assign

    given AssignMethods: AssignMethods with
      extension (self: Assign)
        def lhs: Term = self.lhs
        def rhs: Term = self.rhs
      end extension
    end AssignMethods

    type Block = tpd.Block

    object BlockTypeTest extends TypeTest[Tree, Block]:
      def unapply(x: Tree): Option[Block & x.type] = x match
        case x: (tpd.Block & x.type) => Some(x)
        case _ => None
    end BlockTypeTest

    object Block extends BlockModule:
      def apply(stats: List[Statement], expr: Term): Block =
        withDefaultPos(tpd.Block(stats, expr))
      def copy(original: Tree)(stats: List[Statement], expr: Term): Block =
        tpd.cpy.Block(original)(stats, expr)
      def unapply(x: Block): (List[Statement], Term) =
        (x.statements, x.expr)
    end Block

    given BlockMethods: BlockMethods with
      extension (self: Block)
        def statements: List[Statement] = self.stats
        def expr: Term = self.expr
      end extension
    end BlockMethods

    type Closure = tpd.Closure

    object ClosureTypeTest extends TypeTest[Tree, Closure]:
      def unapply(x: Tree): Option[Closure & x.type] = x match
        case x: (tpd.Closure & x.type) => Some(x)
        case _ => None
    end ClosureTypeTest

    object Closure extends ClosureModule:
      def apply(meth: Term, tpe: Option[TypeRepr]): Closure =
        withDefaultPos(tpd.Closure(Nil, meth, tpe.map(tpd.TypeTree(_)).getOrElse(tpd.EmptyTree)))
      def copy(original: Tree)(meth: Tree, tpe: Option[TypeRepr]): Closure =
        tpd.cpy.Closure(original)(Nil, meth, tpe.map(tpd.TypeTree(_)).getOrElse(tpd.EmptyTree))
      def unapply(x: Closure): (Term, Option[TypeRepr]) =
        (x.meth, x.tpeOpt)
    end Closure

    given ClosureMethods: ClosureMethods with
      extension (self: Closure)
        def meth: Term = self.meth
        def tpeOpt: Option[TypeRepr] = optional(self.tpt).map(_.tpe)
      end extension
    end ClosureMethods

    object Lambda extends LambdaModule:
      def apply(owner: Symbol, tpe: MethodType, rhsFn: (Symbol, List[Tree]) => Tree): Block =
        val meth = dotc.core.Symbols.newSymbol(owner, nme.ANON_FUN, Synthetic | Method, tpe)
        tpd.Closure(meth, tss => yCheckedOwners(rhsFn(meth, tss.head), meth))

      def unapply(tree: Block): Option[(List[ValDef], Term)] = tree match {
        case Block((ddef @ DefDef(_, _, params :: Nil, _, Some(body))) :: Nil, Closure(meth, _))
        if ddef.symbol == meth.symbol =>
          Some((params, body))
        case _ => None
      }
    end Lambda

    type If = tpd.If

    object IfTypeTest extends TypeTest[Tree, If]:
      def unapply(x: Tree): Option[If & x.type] = x match
        case x: (tpd.If & x.type) => Some(x)
        case _ => None
    end IfTypeTest

    object If extends IfModule:
      def apply(cond: Term, thenp: Term, elsep: Term): If =
        withDefaultPos(tpd.If(cond, thenp, elsep))
      def copy(original: Tree)(cond: Term, thenp: Term, elsep: Term): If =
        tpd.cpy.If(original)(cond, thenp, elsep)
      def unapply(tree: If): (Term, Term, Term) =
        (tree.cond, tree.thenp, tree.elsep)
    end If

    given IfMethods: IfMethods with
      extension (self: If)
        def cond: Term = self.cond
        def thenp: Term = self.thenp
        def elsep: Term = self.elsep
        def isInline: Boolean = self.isInline
      end extension
    end IfMethods

    type Match = tpd.Match

    object MatchTypeTest extends TypeTest[Tree, Match]:
      def unapply(x: Tree): Option[Match & x.type] = x match
        case x: (tpd.Match & x.type) if !x.selector.isEmpty => Some(x)
        case _ => None
    end MatchTypeTest

    object Match extends MatchModule:
      def apply(selector: Term, cases: List[CaseDef]): Match =
        withDefaultPos(tpd.Match(selector, cases))

      def copy(original: Tree)(selector: Term, cases: List[CaseDef]): Match =
        tpd.cpy.Match(original)(selector, cases)

      def unapply(x: Match): (Term, List[CaseDef]) =
        (x.scrutinee, x.cases)
    end Match

    given MatchMethods: MatchMethods with
      extension (self: Match)
        def scrutinee: Term = self.selector
        def cases: List[CaseDef] = self.cases
        def isInline: Boolean = self.isInline
      end extension
    end MatchMethods

    type SummonFrom = tpd.Match

    object SummonFromTypeTest extends TypeTest[Tree, SummonFrom]:
      def unapply(x: Tree): Option[SummonFrom & x.type] = x match
        case x: (tpd.Match & x.type) if x.selector.isEmpty => Some(x)
        case _ => None
    end SummonFromTypeTest

    object SummonFrom extends SummonFromModule:
      def apply(cases: List[CaseDef]): SummonFrom =
        withDefaultPos(tpd.Match(tpd.EmptyTree, cases))
      def copy(original: Tree)(cases: List[CaseDef]): SummonFrom =
        tpd.cpy.Match(original)(tpd.EmptyTree, cases)
      def unapply(x: SummonFrom): Some[List[CaseDef]] =
        Some(x.cases)
    end SummonFrom

    given SummonFromMethods: SummonFromMethods with
      extension (self: SummonFrom)
        def cases: List[CaseDef] = self.cases
      end extension
    end SummonFromMethods

    type Try = tpd.Try

    object TryTypeTest extends TypeTest[Tree, Try]:
      def unapply(x: Tree): Option[Try & x.type] = x match
        case x: (tpd.Try & x.type) => Some(x)
        case _ => None
    end TryTypeTest

    object Try extends TryModule:
      def apply(expr: Term, cases: List[CaseDef], finalizer: Option[Term]): Try =
        withDefaultPos(tpd.Try(expr, cases, finalizer.getOrElse(tpd.EmptyTree)))
      def copy(original: Tree)(expr: Term, cases: List[CaseDef], finalizer: Option[Term]): Try =
        tpd.cpy.Try(original)(expr, cases, finalizer.getOrElse(tpd.EmptyTree))
      def unapply(x: Try): (Term, List[CaseDef], Option[Term]) =
        (x.body, x.cases, optional(x.finalizer))
    end Try

    given TryMethods: TryMethods with
      extension (self: Try)
        def body: Term = self.expr
        def cases: List[CaseDef] = self.cases
        def finalizer: Option[Term] = optional(self.finalizer)
      end extension
    end TryMethods

    type Return = tpd.Return

    object ReturnTypeTest extends TypeTest[Tree, Return]:
      def unapply(x: Tree): Option[Return & x.type] = x match
        case x: (tpd.Return & x.type) => Some(x)
        case _ => None
    end ReturnTypeTest

    object Return extends ReturnModule:
      def apply(expr: Term, from: Symbol): Return =
        withDefaultPos(tpd.Return(expr, from))
      def copy(original: Tree)(expr: Term, from: Symbol): Return =
        tpd.cpy.Return(original)(expr, tpd.ref(from))
      def unapply(x: Return): (Term, Symbol) =
        (x.expr, x.from.symbol)
    end Return

    given ReturnMethods: ReturnMethods with
      extension (self: Return)
        def expr: Term = self.expr
        def from: Symbol = self.from.symbol
      end extension
    end ReturnMethods

    type Repeated = tpd.SeqLiteral

    object RepeatedTypeTest extends TypeTest[Tree, Repeated]:
      def unapply(x: Tree): Option[Repeated & x.type] = x match
        case x: (tpd.SeqLiteral & x.type) => Some(x)
        case _ => None
    end RepeatedTypeTest

    object Repeated extends RepeatedModule:
      def apply(elems: List[Term], elemtpt: TypeTree): Repeated =
        withDefaultPos(tpd.SeqLiteral(elems, elemtpt))
      def copy(original: Tree)(elems: List[Term], elemtpt: TypeTree): Repeated =
        tpd.cpy.SeqLiteral(original)(elems, elemtpt)
      def unapply(x: Repeated): (List[Term], TypeTree) =
        (x.elems, x.elemtpt)
    end Repeated

    given RepeatedMethods: RepeatedMethods with
      extension (self: Repeated)
        def elems: List[Term] = self.elems
        def elemtpt: TypeTree = self.elemtpt
      end extension
    end RepeatedMethods

    type Inlined = tpd.Inlined

    object InlinedTypeTest extends TypeTest[Tree, Inlined]:
      def unapply(x: Tree): Option[Inlined & x.type] = x match
        case x: (tpd.Inlined & x.type) => Some(x)
        case _ => None
    end InlinedTypeTest

    object Inlined extends InlinedModule:
      def apply(call: Option[Tree], bindings: List[Definition], expansion: Term): Inlined =
        withDefaultPos(tpd.Inlined(call.getOrElse(tpd.EmptyTree), bindings.map { case b: tpd.MemberDef => b }, expansion))
      def copy(original: Tree)(call: Option[Tree], bindings: List[Definition], expansion: Term): Inlined =
        tpd.cpy.Inlined(original)(call.getOrElse(tpd.EmptyTree), bindings.asInstanceOf[List[tpd.MemberDef]], expansion)
      def unapply(x: Inlined): (Option[Tree /* Term | TypeTree */], List[Definition], Term) =
        (optional(x.call), x.bindings, x.body)
    end Inlined

    given InlinedMethods: InlinedMethods with
      extension (self: Inlined)
        def call: Option[Tree] = optional(self.call)
        def bindings: List[Definition] = self.bindings
        def body: Term = self.expansion
      end extension
    end InlinedMethods

    type SelectOuter = tpd.Select

    object SelectOuterTypeTest extends TypeTest[Tree, SelectOuter]:
      def unapply(x: Tree): Option[SelectOuter & x.type] = x match
        case x: (tpd.Select & x.type) =>
          x.name match
            case NameKinds.OuterSelectName(_, _) => Some(x)
            case _ => None
        case _ => None
    end SelectOuterTypeTest

    object SelectOuter extends SelectOuterModule:
      def apply(qualifier: Term, name: String, levels: Int): SelectOuter =
        withDefaultPos(tpd.Select(qualifier, NameKinds.OuterSelectName(name.toTermName, levels)))
      def copy(original: Tree)(qualifier: Term, name: String, levels: Int): SelectOuter =
        tpd.cpy.Select(original)(qualifier, NameKinds.OuterSelectName(name.toTermName, levels))
      def unapply(x: SelectOuter): (Term, String, Int) =
        (x.qualifier, x.name.toString, x.level)
    end SelectOuter

    given SelectOuterMethods: SelectOuterMethods with
      extension (self: SelectOuter)
        def qualifier: Term = self.qualifier
        def name: String = self.name.toString
        def level: Int =
          val NameKinds.OuterSelectName(_, levels) = self.name
          levels
      end extension
    end SelectOuterMethods

    type While = tpd.WhileDo

    object WhileTypeTest extends TypeTest[Tree, While]:
      def unapply(x: Tree): Option[While & x.type] = x match
        case x: (tpd.WhileDo & x.type) => Some(x)
        case _ => None
    end WhileTypeTest

    object While extends WhileModule:
      def apply(cond: Term, body: Term): While =
        withDefaultPos(tpd.WhileDo(cond, body))
      def copy(original: Tree)(cond: Term, body: Term): While =
        tpd.cpy.WhileDo(original)(cond, body)
      def unapply(x: While): (Term, Term) =
        (x.cond, x.body)
    end While

    given WhileMethods: WhileMethods with
      extension (self: While)
        def cond: Term = self.cond
        def body: Term = self.body
      end extension
    end WhileMethods

    type TypeTree = tpd.Tree

    object TypeTreeTypeTest extends TypeTest[Tree, TypeTree]:
      def unapply(x: Tree): Option[TypeTree & x.type] = x match
        case x: (tpd.TypeBoundsTree & x.type) => None
        case x: (tpd.Tree & x.type) if x.isType => Some(x)
        case _ => None
    end TypeTreeTypeTest

    object TypeTree extends TypeTreeModule:
      def of[T <: AnyKind](using tp: scala.quoted.Type[T]): TypeTree =
        tp.asInstanceOf[TypeImpl].typeTree
    end TypeTree

    given TypeTreeMethods: TypeTreeMethods with
      extension (self: TypeTree)
        def tpe: TypeRepr = self.tpe.stripTypeVar
      end extension
    end TypeTreeMethods

    type Inferred = tpd.TypeTree

    object InferredTypeTest extends TypeTest[Tree, Inferred]:
      def unapply(x: Tree): Option[Inferred & x.type] = x match
        case tpt: (tpd.TypeTree & x.type) if !tpt.tpe.isInstanceOf[Types.TypeBounds] => Some(tpt)
        case _ => None
    end InferredTypeTest

    object Inferred extends InferredModule:
      def apply(tpe: TypeRepr): Inferred =
        withDefaultPos(tpd.TypeTree(tpe))
      def unapply(x: Inferred): true = true
    end Inferred

    type TypeIdent = tpd.Ident

    object TypeIdentTypeTest extends TypeTest[Tree, TypeIdent]:
      def unapply(x: Tree): Option[TypeIdent & x.type] = x match
        case tpt: (tpd.Ident & x.type) if tpt.isType => Some(tpt)
        case _ => None
    end TypeIdentTypeTest

    object TypeIdent extends TypeIdentModule:
      def apply(sym: Symbol): TypeTree =
        assert(sym.isType)
        withDefaultPos(tpd.ref(sym).asInstanceOf[tpd.TypeTree])
      def copy(original: Tree)(name: String): TypeIdent =
        tpd.cpy.Ident(original)(name.toTypeName)
      def unapply(x: TypeIdent): Some[String] =
        Some(x.name.toString)
    end TypeIdent

    given TypeIdentMethods: TypeIdentMethods with
      extension (self: TypeIdent)
        def name: String = self.name.toString
      end extension
    end TypeIdentMethods

    type TypeSelect = tpd.Select

    object TypeSelectTypeTest extends TypeTest[Tree, TypeSelect]:
      def unapply(x: Tree): Option[TypeSelect & x.type] = x match
        case tpt: (tpd.Select & x.type) if tpt.isType && tpt.qualifier.isTerm  => Some(tpt)
        case _ => None
    end TypeSelectTypeTest

    object TypeSelect extends TypeSelectModule:
      def apply(qualifier: Term, name: String): TypeSelect =
        withDefaultPos(tpd.Select(qualifier, name.toTypeName))
      def copy(original: Tree)(qualifier: Term, name: String): TypeSelect =
        tpd.cpy.Select(original)(qualifier, name.toTypeName)
      def unapply(x: TypeSelect): (Term, String) =
        (x.qualifier, x.name.toString)
    end TypeSelect

    given TypeSelectMethods: TypeSelectMethods with
      extension (self: TypeSelect)
        def qualifier: Term = self.qualifier
        def name: String = self.name.toString
      end extension
    end TypeSelectMethods

    type TypeProjection = tpd.Select

    object TypeProjectionTypeTest extends TypeTest[Tree, TypeProjection]:
      def unapply(x: Tree): Option[TypeProjection & x.type] = x match
        case tpt: (tpd.Select & x.type) if tpt.isType && tpt.qualifier.isType => Some(tpt)
        case _ => None
    end TypeProjectionTypeTest

    object TypeProjection extends TypeProjectionModule:
      def copy(original: Tree)(qualifier: TypeTree, name: String): TypeProjection =
        tpd.cpy.Select(original)(qualifier, name.toTypeName)
      def unapply(x: TypeProjection): (TypeTree, String) =
        (x.qualifier, x.name.toString)
    end TypeProjection

    given TypeProjectionMethods: TypeProjectionMethods with
      extension (self: TypeProjection)
        def qualifier: TypeTree = self.qualifier
        def name: String = self.name.toString
      end extension
    end TypeProjectionMethods

    type Singleton = tpd.SingletonTypeTree

    object SingletonTypeTest extends TypeTest[Tree, Singleton]:
      def unapply(x: Tree): Option[Singleton & x.type] = x match
        case tpt: (tpd.SingletonTypeTree & x.type) => Some(tpt)
        case _ => None
    end SingletonTypeTest

    object Singleton extends SingletonModule:
      def apply(ref: Term): Singleton =
        withDefaultPos(tpd.SingletonTypeTree(ref))
      def copy(original: Tree)(ref: Term): Singleton =
        tpd.cpy.SingletonTypeTree(original)(ref)
      def unapply(x: Singleton): Some[Term] =
        Some(x.ref)
    end Singleton

    given SingletonMethods: SingletonMethods with
      extension (self: Singleton)
        def ref: Term = self.ref
      end extension
    end SingletonMethods

    type Refined = tpd.RefinedTypeTree

    object RefinedTypeTest extends TypeTest[Tree, Refined]:
      def unapply(x: Tree): Option[Refined & x.type] = x match
        case tpt: (tpd.RefinedTypeTree & x.type) => Some(tpt)
        case _ => None
    end RefinedTypeTest

    object Refined extends RefinedModule:
      def copy(original: Tree)(tpt: TypeTree, refinements: List[Definition]): Refined =
        tpd.cpy.RefinedTypeTree(original)(tpt, refinements)
      def unapply(x: Refined): (TypeTree, List[Definition]) =
        (x.tpt, x.refinements.asInstanceOf[List[Definition]])
    end Refined

    given RefinedMethods: RefinedMethods with
      extension (self: Refined)
        def tpt: TypeTree = self.tpt
        def refinements: List[Definition] = self.refinements.asInstanceOf[List[Definition]]
      end extension
    end RefinedMethods

    type Applied = tpd.AppliedTypeTree

    object AppliedTypeTest extends TypeTest[Tree, Applied]:
      def unapply(x: Tree): Option[Applied & x.type] = x match
        case tpt: (tpd.AppliedTypeTree & x.type) => Some(tpt)
        case _ => None
    end AppliedTypeTest

    object Applied extends AppliedModule:
      def apply(tpt: TypeTree, args: List[Tree]): Applied =
        withDefaultPos(tpd.AppliedTypeTree(tpt, args))
      def copy(original: Tree)(tpt: TypeTree, args: List[Tree]): Applied =
        tpd.cpy.AppliedTypeTree(original)(tpt, args)
      def unapply(x: Applied): (TypeTree, List[Tree /*TypeTree | TypeBoundsTree*/]) =
        (x.tpt, x.args)
    end Applied

    given AppliedMethods: AppliedMethods with
      extension (self: Applied)
        def tpt: TypeTree = self.tpt
        def args: List[Tree] = self.args
      end extension
    end AppliedMethods

    type Annotated = tpd.Annotated

    object AnnotatedTypeTest extends TypeTest[Tree, Annotated]:
      def unapply(x: Tree): Option[Annotated & x.type] = x match
        case tpt: (tpd.Annotated & x.type) => Some(tpt)
        case _ => None
    end AnnotatedTypeTest

    object Annotated extends AnnotatedModule:
      def apply(arg: TypeTree, annotation: Term): Annotated =
        withDefaultPos(tpd.Annotated(arg, annotation))
      def copy(original: Tree)(arg: TypeTree, annotation: Term): Annotated =
        tpd.cpy.Annotated(original)(arg, annotation)
      def unapply(x: Annotated): (TypeTree, Term) =
        (x.arg, x.annotation)
    end Annotated

    given AnnotatedMethods: AnnotatedMethods with
      extension (self: Annotated)
        def arg: TypeTree = self.arg
        def annotation: Term = self.annot
      end extension
    end AnnotatedMethods

    type MatchTypeTree = tpd.MatchTypeTree

    object MatchTypeTreeTypeTest extends TypeTest[Tree, MatchTypeTree]:
      def unapply(x: Tree): Option[MatchTypeTree & x.type] = x match
        case tpt: (tpd.MatchTypeTree & x.type) => Some(tpt)
        case _ => None
    end MatchTypeTreeTypeTest

    object MatchTypeTree extends MatchTypeTreeModule:
      def apply(bound: Option[TypeTree], selector: TypeTree, cases: List[TypeCaseDef]): MatchTypeTree =
        withDefaultPos(tpd.MatchTypeTree(bound.getOrElse(tpd.EmptyTree), selector, cases))
      def copy(original: Tree)(bound: Option[TypeTree], selector: TypeTree, cases: List[TypeCaseDef]): MatchTypeTree =
        tpd.cpy.MatchTypeTree(original)(bound.getOrElse(tpd.EmptyTree), selector, cases)
      def unapply(x: MatchTypeTree): (Option[TypeTree], TypeTree, List[TypeCaseDef]) =
        (optional(x.bound), x.selector, x.cases)
    end MatchTypeTree

    given MatchTypeTreeMethods: MatchTypeTreeMethods with
      extension (self: MatchTypeTree)
        def bound: Option[TypeTree] = optional(self.bound)
        def selector: TypeTree = self.selector
        def cases: List[TypeCaseDef] = self.cases
      end extension
    end MatchTypeTreeMethods

    type ByName = tpd.ByNameTypeTree

    object ByNameTypeTest extends TypeTest[Tree, ByName]:
      def unapply(x: Tree): Option[ByName & x.type] = x match
        case tpt: (tpd.ByNameTypeTree & x.type) => Some(tpt)
        case _ => None
    end ByNameTypeTest

    object ByName extends ByNameModule:
      def apply(result: TypeTree): ByName =
        withDefaultPos(tpd.ByNameTypeTree(result))
      def copy(original: Tree)(result: TypeTree): ByName =
        tpd.cpy.ByNameTypeTree(original)(result)
      def unapply(x: ByName): Some[TypeTree] =
        Some(x.result)
    end ByName

    given ByNameMethods: ByNameMethods with
      extension (self: ByName)
        def result: TypeTree = self.result
      end extension
    end ByNameMethods

    type LambdaTypeTree = tpd.LambdaTypeTree

    object LambdaTypeTreeTypeTest extends TypeTest[Tree, LambdaTypeTree]:
      def unapply(x: Tree): Option[LambdaTypeTree & x.type] = x match
        case tpt: (tpd.LambdaTypeTree & x.type) => Some(tpt)
        case _ => None
    end LambdaTypeTreeTypeTest

    object LambdaTypeTree extends LambdaTypeTreeModule:
      def apply(tparams: List[TypeDef], body: Tree): LambdaTypeTree =
        withDefaultPos(tpd.LambdaTypeTree(tparams, body))
      def copy(original: Tree)(tparams: List[TypeDef], body: Tree): LambdaTypeTree =
        tpd.cpy.LambdaTypeTree(original)(tparams, body)
      def unapply(tree: LambdaTypeTree): (List[TypeDef], Tree /*TypeTree | TypeBoundsTree*/) =
        (tree.tparams, tree.body)
    end LambdaTypeTree

    given LambdaTypeTreeMethods: LambdaTypeTreeMethods with
      extension (self: LambdaTypeTree)
        def tparams: List[TypeDef] = self.tparams
        def body: Tree = self.body
      end extension
    end LambdaTypeTreeMethods

    type TypeBind = tpd.Bind

    object TypeBindTypeTest extends TypeTest[Tree, TypeBind]:
      def unapply(x: Tree): Option[TypeBind & x.type] = x match
        case tpt: (tpd.Bind & x.type) if tpt.name.isTypeName => Some(tpt)
        case _ => None
    end TypeBindTypeTest

    object TypeBind extends TypeBindModule:
      def copy(original: Tree)(name: String, tpt: Tree): TypeBind =
        tpd.cpy.Bind(original)(name.toTypeName, tpt)
      def unapply(x: TypeBind): (String, Tree /*TypeTree | TypeBoundsTree*/) =
        (x.name.toString, x.body)
    end TypeBind

    given TypeBindMethods: TypeBindMethods with
      extension (self: TypeBind)
        def name: String = self.name.toString
        def body: Tree = self.body
      end extension
    end TypeBindMethods

    type TypeBlock = tpd.Block

    object TypeBlockTypeTest extends TypeTest[Tree, TypeBlock]:
      def unapply(x: Tree): Option[TypeBlock & x.type] = x match
        case tpt: (tpd.Block & x.type) => Some(tpt)
        case _ => None
    end TypeBlockTypeTest

    object TypeBlock extends TypeBlockModule:
      def apply(aliases: List[TypeDef], tpt: TypeTree): TypeBlock =
        withDefaultPos(tpd.Block(aliases, tpt))
      def copy(original: Tree)(aliases: List[TypeDef], tpt: TypeTree): TypeBlock =
        tpd.cpy.Block(original)(aliases, tpt)
      def unapply(x: TypeBlock): (List[TypeDef], TypeTree) =
        (x.aliases, x.tpt)
    end TypeBlock

    given TypeBlockMethods: TypeBlockMethods with
      extension (self: TypeBlock)
        def aliases: List[TypeDef] = self.stats.map { case alias: TypeDef => alias }
        def tpt: TypeTree = self.expr
      end extension
    end TypeBlockMethods

    type TypeBoundsTree = tpd.TypeBoundsTree | tpd.TypeTree

    object TypeBoundsTreeTypeTest extends TypeTest[Tree, TypeBoundsTree]:
      def unapply(x: Tree): Option[TypeBoundsTree & x.type] = x match
        case x: (tpd.TypeBoundsTree & x.type) => Some(x)
        case x: (tpd.TypeTree & x.type) =>
          x.tpe match
            case tpe: Types.TypeBounds => Some(x)
            case _ => None
        case _ => None
    end TypeBoundsTreeTypeTest

    object TypeBoundsTree extends TypeBoundsTreeModule:
      def apply(low: TypeTree, hi: TypeTree): TypeBoundsTree =
        withDefaultPos(tpd.TypeBoundsTree(low, hi))
      def copy(original: Tree)(low: TypeTree, hi: TypeTree): TypeBoundsTree =
        tpd.cpy.TypeBoundsTree(original)(low, hi, tpd.EmptyTree)
      def unapply(x: TypeBoundsTree): (TypeTree, TypeTree) =
        (x.low, x.hi)
    end TypeBoundsTree

    given TypeBoundsTreeMethods: TypeBoundsTreeMethods with
      extension (self: TypeBoundsTree)
        def tpe: TypeBounds = self.tpe.asInstanceOf[Types.TypeBounds]
        def low: TypeTree = self match
          case self: tpd.TypeBoundsTree => self.lo
          case self: tpd.TypeTree => tpd.TypeTree(self.tpe.asInstanceOf[Types.TypeBounds].lo).withSpan(self.span)
        def hi: TypeTree = self match
          case self: tpd.TypeBoundsTree => self.hi
          case self: tpd.TypeTree => tpd.TypeTree(self.tpe.asInstanceOf[Types.TypeBounds].hi).withSpan(self.span)
      end extension
    end TypeBoundsTreeMethods

    type WildcardTypeTree = tpd.Ident

    object WildcardTypeTreeTypeTest extends TypeTest[Tree, WildcardTypeTree]:
      def unapply(x: Tree): Option[WildcardTypeTree & x.type] = x match
        case x: (tpd.Ident & x.type) if x.name == nme.WILDCARD => Some(x)
        case _ => None
    end WildcardTypeTreeTypeTest

    object WildcardTypeTree extends WildcardTypeTreeModule:
      def apply(tpe: TypeRepr): WildcardTypeTree = withDefaultPos(tpd.Underscore(tpe))
      def unapply(x: WildcardTypeTree): true = true
    end WildcardTypeTree

    given WildcardTypeTreeMethods: WildcardTypeTreeMethods with
      extension (self: WildcardTypeTree)
        def tpe: TypeRepr = self.tpe.stripTypeVar
      end extension
    end WildcardTypeTreeMethods

    type CaseDef = tpd.CaseDef

    object CaseDefTypeTest extends TypeTest[Tree, CaseDef]:
      def unapply(x: Tree): Option[CaseDef & x.type] = x match
        case tree: (tpd.CaseDef & x.type) if tree.body.isTerm => Some(tree)
        case _ => None
    end CaseDefTypeTest

    object CaseDef extends CaseDefModule:
      def apply(pattern: Tree, guard: Option[Term], rhs: Term): CaseDef =
        tpd.CaseDef(pattern, guard.getOrElse(tpd.EmptyTree), rhs)
      def copy(original: Tree)(pattern: Tree, guard: Option[Term], rhs: Term): CaseDef =
        tpd.cpy.CaseDef(original)(pattern, guard.getOrElse(tpd.EmptyTree), rhs)
      def unapply(x: CaseDef): (Tree, Option[Term], Term) =
        (x.pat, optional(x.guard), x.body)
    end CaseDef

    given CaseDefMethods: CaseDefMethods with
      extension (self: CaseDef)
        def pattern: Tree = self.pat
        def guard: Option[Term] = optional(self.guard)
        def rhs: Term = self.body
      end extension
    end CaseDefMethods

    type TypeCaseDef = tpd.CaseDef

    object TypeCaseDefTypeTest extends TypeTest[Tree, TypeCaseDef]:
      def unapply(x: Tree): Option[TypeCaseDef & x.type] = x match
        case tree: (tpd.CaseDef & x.type) if tree.body.isType => Some(tree)
        case _ => None
    end TypeCaseDefTypeTest

    object TypeCaseDef extends TypeCaseDefModule:
      def apply(pattern: TypeTree, rhs: TypeTree): TypeCaseDef =
        tpd.CaseDef(pattern, tpd.EmptyTree, rhs)
      def copy(original: Tree)(pattern: TypeTree, rhs: TypeTree): TypeCaseDef =
        tpd.cpy.CaseDef(original)(pattern, tpd.EmptyTree, rhs)
      def unapply(tree: TypeCaseDef): (TypeTree, TypeTree) =
        (tree.pat, tree.body)
    end TypeCaseDef

    given TypeCaseDefMethods: TypeCaseDefMethods with
      extension (self: TypeCaseDef)
        def pattern: TypeTree = self.pat
        def rhs: TypeTree = self.body
      end extension
    end TypeCaseDefMethods

    type Bind = tpd.Bind

    object BindTypeTest extends TypeTest[Tree, Bind]:
      def unapply(x: Tree): Option[Bind & x.type] = x match
        case x: (tpd.Bind & x.type) if x.name.isTermName => Some(x)
        case _ => None
    end BindTypeTest

    object Bind extends BindModule:
      def apply(sym: Symbol, pattern: Tree): Bind =
        tpd.Bind(sym, pattern)
      def copy(original: Tree)(name: String, pattern: Tree): Bind =
        withDefaultPos(tpd.cpy.Bind(original)(name.toTermName, pattern))
      def unapply(pattern: Bind): (String, Tree) =
        (pattern.name.toString, pattern.pattern)
    end Bind

    given BindMethods: BindMethods with
      extension (self: Bind)
        def name: String = self.name.toString
        def pattern: Tree = self.body
      end extension
    end BindMethods

    type Unapply = tpd.UnApply | tpd.Typed // tpd.Typed containing a tpd.UnApply as expression

    object UnapplyTypeTest extends TypeTest[Tree, Unapply]:
      def unapply(x: Tree): Option[Unapply & x.type] =
        x match // keep in sync with UnapplyMethodsImpl.selfUnApply
          case x: (tpd.UnApply & x.type) => Some(x)
          case x: (tpd.Typed & x.type) if x.expr.isInstanceOf[tpd.UnApply] => Some(x)
          case _ => None
    end UnapplyTypeTest

    object Unapply extends UnapplyModule:
      def copy(original: Tree)(fun: Term, implicits: List[Term], patterns: List[Tree]): Unapply =
        withDefaultPos(tpd.cpy.UnApply(original)(fun, implicits, patterns))
      def unapply(x: Unapply): (Term, List[Term], List[Tree]) =
        (x.fun, x.implicits, x.patterns)
    end Unapply

    given UnapplyMethods: UnapplyMethods with
      extension (self: Unapply)
        def fun: Term = selfUnApply(self).fun
        def implicits: List[Term] = selfUnApply(self).implicits
        def patterns: List[Tree] = effectivePatterns(selfUnApply(self).patterns)
      end extension
      private def selfUnApply(self: Unapply): tpd.UnApply =
        self match // keep in sync with UnapplyTypeTest
          case self: tpd.UnApply => self
          case self: tpd.Typed => self.expr.asInstanceOf[tpd.UnApply]
      private def effectivePatterns(patterns: List[Tree]): List[Tree] =
        patterns match
          case patterns0 :+ dotc.ast.Trees.SeqLiteral(elems, _) => patterns0 ::: elems
          case _ => patterns
    end UnapplyMethods

    type Alternatives = tpd.Alternative

    object AlternativesTypeTest extends TypeTest[Tree, Alternatives]:
      def unapply(x: Tree): Option[Alternatives & x.type] = x match
        case x: (tpd.Alternative & x.type) => Some(x)
        case _ => None
    end AlternativesTypeTest

    object Alternatives extends AlternativesModule:
      def apply(patterns: List[Tree]): Alternatives =
        withDefaultPos(tpd.Alternative(patterns))
      def copy(original: Tree)(patterns: List[Tree]): Alternatives =
        tpd.cpy.Alternative(original)(patterns)
      def unapply(x: Alternatives): Some[List[Tree]] =
        Some(x.patterns)
    end Alternatives

    given AlternativesMethods: AlternativesMethods with
      extension (self: Alternatives)
        def patterns: List[Tree] = self.trees
      end extension
    end AlternativesMethods

    type Selector = untpd.ImportSelector

    object Selector extends SelectorModule

    type SimpleSelector = untpd.ImportSelector

    object SimpleSelectorTypeTest extends TypeTest[Selector, SimpleSelector]:
      def unapply(x: Selector): Option[SimpleSelector & x.type] = x match
        case x: (untpd.ImportSelector & x.type) if x.renamed.isEmpty && !x.isGiven => Some(x)
        case _ => None // TODO: handle import bounds
    end SimpleSelectorTypeTest

    object SimpleSelector extends SimpleSelectorModule:
      def unapply(x: SimpleSelector): Some[String] = Some(x.name.toString)
    end SimpleSelector

    given SimpleSelectorMethods: SimpleSelectorMethods with
      extension (self: SimpleSelector)
        def name: String = self.imported.name.toString
        def namePos: Position = self.imported.sourcePos
      end extension
    end SimpleSelectorMethods

    type RenameSelector = untpd.ImportSelector

    object RenameSelectorTypeTest extends TypeTest[Selector, RenameSelector]:
      def unapply(x: Selector): Option[RenameSelector & x.type] = x match
        case x: (untpd.ImportSelector & x.type) if !x.renamed.isEmpty => Some(x)
        case _ => None
    end RenameSelectorTypeTest

    object RenameSelector extends RenameSelectorModule:
      def unapply(x: RenameSelector): (String, String) = (x.fromName, x.toName)
    end RenameSelector

    given RenameSelectorMethods: RenameSelectorMethods with
      extension (self: RenameSelector)
        def fromName: String = self.imported.name.toString
        def fromPos: Position = self.imported.sourcePos
        def toName: String = self.renamed.asInstanceOf[untpd.Ident].name.toString
        def toPos: Position = self.renamed.asInstanceOf[untpd.Ident].sourcePos
      end extension
    end RenameSelectorMethods

    type OmitSelector = untpd.ImportSelector

    object OmitSelectorTypeTest extends TypeTest[Selector, OmitSelector]:
      def unapply(x: Selector): Option[OmitSelector & x.type] = x match {
        case self: (untpd.ImportSelector & x.type) =>
          self.renamed match
            case dotc.ast.Trees.Ident(nme.WILDCARD) => Some(self)
            case _ => None
        case _ => None
      }
    end OmitSelectorTypeTest

    object OmitSelector extends OmitSelectorModule:
      def unapply(x: OmitSelector): Some[String] = Some(x.imported.name.toString)
    end OmitSelector

    given OmitSelectorMethods: OmitSelectorMethods with
      extension (self: OmitSelector)
        def name: String = self.imported.toString
        def namePos: Position = self.imported.sourcePos
      end extension
    end OmitSelectorMethods

    type GivenSelector = untpd.ImportSelector

    object GivenSelectorTypeTest extends TypeTest[Selector, GivenSelector]:
      def unapply(x: Selector): Option[GivenSelector & x.type] = x match {
        case self: (untpd.ImportSelector & x.type) if x.isGiven => Some(self)
        case _ => None
      }
    end GivenSelectorTypeTest

    object GivenSelector extends GivenSelectorModule:
      def unapply(x: GivenSelector): Some[Option[TypeTree]] =
        Some(GivenSelectorMethods.bound(x))
    end GivenSelector

    given GivenSelectorMethods: GivenSelectorMethods with
      extension (self: GivenSelector)
        def bound: Option[TypeTree] =
          self.bound match
            case untpd.TypedSplice(tpt) => Some(tpt)
            case _ => None
      end extension
    end GivenSelectorMethods

    type TypeRepr = dotc.core.Types.Type

    object TypeRepr extends TypeReprModule:
      def of[T <: AnyKind](using tp: scala.quoted.Type[T]): TypeRepr =
        tp.asInstanceOf[TypeImpl].typeTree.tpe
      def typeConstructorOf(clazz: Class[?]): TypeRepr =
        if (clazz.isPrimitive)
          if (clazz == classOf[Boolean]) dotc.core.Symbols.defn.BooleanType
          else if (clazz == classOf[Byte]) dotc.core.Symbols.defn.ByteType
          else if (clazz == classOf[Char]) dotc.core.Symbols.defn.CharType
          else if (clazz == classOf[Short]) dotc.core.Symbols.defn.ShortType
          else if (clazz == classOf[Int]) dotc.core.Symbols.defn.IntType
          else if (clazz == classOf[Long]) dotc.core.Symbols.defn.LongType
          else if (clazz == classOf[Float]) dotc.core.Symbols.defn.FloatType
          else if (clazz == classOf[Double]) dotc.core.Symbols.defn.DoubleType
          else dotc.core.Symbols.defn.UnitType
        else if (clazz.isArray)
          dotc.core.Symbols.defn.ArrayType.appliedTo(typeConstructorOf(clazz.getComponentType))
        else if clazz.isMemberClass then
          val name = clazz.getSimpleName.toTypeName
          val enclosing = typeConstructorOf(clazz.getEnclosingClass)
          if (enclosing.member(name).exists) enclosing.select(name)
          else enclosing.classSymbol.companionModule.termRef.select(name)
        else
          dotc.core.Symbols.getClassIfDefined(clazz.getCanonicalName).typeRef
    end TypeRepr

    given TypeReprMethods: TypeReprMethods with
      extension (self: TypeRepr)

        def show(using printer: Printer[TypeRepr]): String = printer.show(self)

        def seal: scala.quoted.Type[_] = self.asType

        def asType: scala.quoted.Type[?] =
          new TypeImpl(Inferred(self), QuotesImpl.this.hashCode)

        def =:=(that: TypeRepr): Boolean = self =:= that
        def <:<(that: TypeRepr): Boolean = self <:< that
        def widen: TypeRepr = self.widen
        def widenTermRefExpr: TypeRepr = self.widenTermRefExpr
        def dealias: TypeRepr = self.dealias
        def simplified: TypeRepr = self.simplified
        def classSymbol: Option[Symbol] =
          if self.classSymbol.exists then Some(self.classSymbol.asClass)
          else None
        def typeSymbol: Symbol = self.typeSymbol
        def termSymbol: Symbol = self.termSymbol
        def isSingleton: Boolean = self.isSingleton
        def memberType(member: Symbol): TypeRepr =
          member.info.asSeenFrom(self, member.owner)
        def baseClasses: List[Symbol] = self.baseClasses
        def baseType(cls: Symbol): TypeRepr = self.baseType(cls)
        def derivesFrom(cls: Symbol): Boolean = self.derivesFrom(cls)
        def isFunctionType: Boolean =
          dotc.core.Symbols.defn.isFunctionType(self)
        def isContextFunctionType: Boolean =
          dotc.core.Symbols.defn.isContextFunctionType(self)
        def isErasedFunctionType: Boolean =
          dotc.core.Symbols.defn.isErasedFunctionType(self)
        def isDependentFunctionType: Boolean =
          val tpNoRefinement = self.dropDependentRefinement
          tpNoRefinement != self
          && dotc.core.Symbols.defn.isNonRefinedFunction(tpNoRefinement)
        def select(sym: Symbol): TypeRepr = self.select(sym)
        def appliedTo(targ: TypeRepr): TypeRepr =
          dotc.core.Types.decorateTypeApplications(self).appliedTo(targ)
        def appliedTo(targs: List[TypeRepr]): TypeRepr =
          dotc.core.Types.decorateTypeApplications(self).appliedTo(targs)
      end extension
    end TypeReprMethods

    type ConstantType = dotc.core.Types.ConstantType

    object ConstantTypeTypeTest extends TypeTest[TypeRepr, ConstantType]:
      def unapply(x: TypeRepr): Option[ConstantType & x.type] = x match
        case tpe: (Types.ConstantType & x.type) => Some(tpe)
        case _ => None
    end ConstantTypeTypeTest

    object ConstantType extends ConstantTypeModule:
      def apply(const: Constant): ConstantType = Types.ConstantType(const)
      def unapply(x: ConstantType): Some[Constant] = Some(x.constant)
    end ConstantType

    given ConstantTypeMethods: ConstantTypeMethods with
      extension (self: ConstantType) def constant: Constant = self.value
    end ConstantTypeMethods

    type NamedType = dotc.core.Types.NamedType

    object NamedTypeTypeTest extends TypeTest[TypeRepr, NamedType]:
      def unapply(x: TypeRepr): Option[NamedType & x.type] = x match
        case tpe: (Types.NamedType & x.type) => Some(tpe)
        case _ => None
    end NamedTypeTypeTest

    given NamedTypeMethods: NamedTypeMethods with
      extension (self: NamedType)
        def qualifier: TypeRepr = self.prefix
        def name: String = self.name.toString
      end extension
    end NamedTypeMethods

    type TermRef = dotc.core.Types.NamedType

    object TermRefTypeTest extends TypeTest[TypeRepr, TermRef]:
      def unapply(x: TypeRepr): Option[TermRef & x.type] = x match
        case tpe: (Types.TermRef & x.type) => Some(tpe)
        case _ => None
    end TermRefTypeTest

    object TermRef extends TermRefModule:
      def apply(qual: TypeRepr, name: String): TermRef =
        Types.TermRef(qual, name.toTermName)
      def unapply(x: TermRef): (TypeRepr, String) =
        (x.prefix, x.name.toString)
    end TermRef

    type TypeRef = dotc.core.Types.NamedType

    object TypeRefTypeTest extends TypeTest[TypeRepr, TypeRef]:
      def unapply(x: TypeRepr): Option[TypeRef & x.type] = x match
        case tpe: (Types.TypeRef & x.type) => Some(tpe)
        case _ => None
    end TypeRefTypeTest

    object TypeRef extends TypeRefModule:
      def unapply(x: TypeRef): (TypeRepr, String) =
        (x.prefix, x.name.toString)
    end TypeRef

    given TypeRefMethods: TypeRefMethods with
      extension (self: TypeRef)
        def isOpaqueAlias: Boolean = self.symbol.isOpaqueAlias
        def translucentSuperType: TypeRepr = self.translucentSuperType
      end extension
    end TypeRefMethods

    type SuperType = dotc.core.Types.SuperType

    object SuperTypeTypeTest extends TypeTest[TypeRepr, SuperType]:
      def unapply(x: TypeRepr): Option[SuperType & x.type] = x match
        case tpe: (Types.SuperType & x.type) => Some(tpe)
        case _ => None
    end SuperTypeTypeTest

    object SuperType extends SuperTypeModule:
      def apply(thistpe: TypeRepr, supertpe: TypeRepr): SuperType =
        Types.SuperType(thistpe, supertpe)
      def unapply(x: SuperType): (TypeRepr, TypeRepr) =
        (x.thistpe, x.supertpe)
    end SuperType

    given SuperTypeMethods: SuperTypeMethods with
      extension (self: SuperType)
        def thistpe: TypeRepr = self.thistpe
        def supertpe: TypeRepr = self.thistpe
      end extension
    end SuperTypeMethods

    type Refinement = dotc.core.Types.RefinedType

    object RefinementTypeTest extends TypeTest[TypeRepr, Refinement]:
      def unapply(x: TypeRepr): Option[Refinement & x.type] = x match
        case tpe: (Types.RefinedType & x.type) => Some(tpe)
        case _ => None
    end RefinementTypeTest

    object Refinement extends RefinementModule:
      def apply(parent: TypeRepr, name: String, info: TypeRepr): Refinement =
        val name1 =
          info match
            case _: TypeBounds => name.toTypeName
            case _ => name.toTermName
        Types.RefinedType(parent, name1, info)
      def unapply(x: Refinement): (TypeRepr, String, TypeRepr) =
        (x.parent, x.name, x.info)
    end Refinement

    given RefinementMethods: RefinementMethods with
      extension (self: Refinement)
        def parent: TypeRepr = self.parent
        def name: String = self.refinedName.toString
        def info: TypeRepr = self.refinedInfo
      end extension
    end RefinementMethods

    type AppliedType = dotc.core.Types.AppliedType

    object AppliedTypeTypeTest extends TypeTest[TypeRepr, AppliedType]:
      def unapply(x: TypeRepr): Option[AppliedType & x.type] = x match
        case tpe: (Types.AppliedType & x.type) => Some(tpe)
        case _ => None
    end AppliedTypeTypeTest

    object AppliedType extends AppliedTypeModule:
      def unapply(x: AppliedType): (TypeRepr, List[TypeRepr]) =
        (x.tycon, x.args)
    end AppliedType

    given AppliedTypeMethods: AppliedTypeMethods with
      extension (self: AppliedType)
        def tycon: TypeRepr = self.tycon
        def args: List[TypeRepr] = self.args
      end extension
    end AppliedTypeMethods

    type AnnotatedType = dotc.core.Types.AnnotatedType

    object AnnotatedTypeTypeTest extends TypeTest[TypeRepr, AnnotatedType]:
      def unapply(x: TypeRepr): Option[AnnotatedType & x.type] = x match
        case tpe: (Types.AnnotatedType & x.type) => Some(tpe)
        case _ => None
    end AnnotatedTypeTypeTest

    object AnnotatedType extends AnnotatedTypeModule:
      def apply(underlying: TypeRepr, annot: Term): AnnotatedType =
        Types.AnnotatedType(underlying, Annotations.Annotation(annot))
      def unapply(x: AnnotatedType): (TypeRepr, Term) =
        (x.underlying.stripTypeVar, x.annot.tree)
    end AnnotatedType

    given AnnotatedTypeMethods: AnnotatedTypeMethods with
      extension (self: AnnotatedType)
        def underlying: TypeRepr = self.underlying.stripTypeVar
        def annotation: Term = self.annot.tree
      end extension
    end AnnotatedTypeMethods

    type AndOrType = dotc.core.Types.AndOrType

    object AndOrTypeTypeTest extends TypeTest[TypeRepr, AndOrType]:
      def unapply(x: TypeRepr): Option[AndOrType & x.type] = x match
        case tpe: (Types.AndOrType & x.type) => Some(tpe)
        case _ => None
    end AndOrTypeTypeTest

    given AndOrTypeMethods: AndOrTypeMethods with
      extension (self: AndOrType)
        def left: TypeRepr = self.tp1.stripTypeVar
        def right: TypeRepr = self.tp2.stripTypeVar
      end extension
    end AndOrTypeMethods

    type AndType = dotc.core.Types.AndType

    object AndTypeTypeTest extends TypeTest[TypeRepr, AndType]:
      def unapply(x: TypeRepr): Option[AndType & x.type] = x match
        case tpe: (Types.AndType & x.type) => Some(tpe)
        case _ => None
    end AndTypeTypeTest

    object AndType extends AndTypeModule:
      def apply(lhs: TypeRepr, rhs: TypeRepr): AndType = Types.AndType(lhs, rhs)
      def unapply(x: AndType): (TypeRepr, TypeRepr) = (x.left, x.right)
    end AndType

    type OrType = dotc.core.Types.OrType

    object OrTypeTypeTest extends TypeTest[TypeRepr, OrType]:
      def unapply(x: TypeRepr): Option[OrType & x.type] = x match
        case tpe: (Types.OrType & x.type) => Some(tpe)
        case _ => None
    end OrTypeTypeTest

    object OrType extends OrTypeModule:
      def apply(lhs: TypeRepr, rhs: TypeRepr): OrType = Types.OrType(lhs, rhs, soft = false)
      def unapply(x: OrType): (TypeRepr, TypeRepr) = (x.left, x.right)
    end OrType

    type MatchType = dotc.core.Types.MatchType

    object MatchTypeTypeTest extends TypeTest[TypeRepr, MatchType]:
      def unapply(x: TypeRepr): Option[MatchType & x.type] = x match
        case tpe: (Types.MatchType & x.type) => Some(tpe)
        case _ => None
    end MatchTypeTypeTest

    object MatchType extends MatchTypeModule:
      def apply(bound: TypeRepr, scrutinee: TypeRepr, cases: List[TypeRepr]): MatchType =
        Types.MatchType(bound, scrutinee, cases)
      def unapply(x: MatchType): (TypeRepr, TypeRepr, List[TypeRepr]) =
        (x.bound, x.scrutinee, x.cases)
    end MatchType

    given MatchTypeMethods: MatchTypeMethods with
      extension (self: MatchType)
        def bound: TypeRepr = self.bound
        def scrutinee: TypeRepr = self.scrutinee
        def cases: List[TypeRepr] = self.cases
      end extension
    end MatchTypeMethods

    type ByNameType = dotc.core.Types.ExprType

    object ByNameTypeTypeTest extends TypeTest[TypeRepr, ByNameType]:
      def unapply(x: TypeRepr): Option[ByNameType & x.type] = x match
        case tpe: (Types.ExprType & x.type) => Some(tpe)
        case _ => None
    end ByNameTypeTypeTest

    object ByNameType extends ByNameTypeModule:
      def apply(underlying: TypeRepr): TypeRepr = Types.ExprType(underlying)
      def unapply(x: ByNameType): Some[TypeRepr] = Some(x.underlying)
    end ByNameType

    given ByNameTypeMethods: ByNameTypeMethods with
      extension (self: ByNameType)
        def underlying: TypeRepr = self.resType.stripTypeVar
      end extension
    end ByNameTypeMethods

    type ParamRef = dotc.core.Types.ParamRef

    object ParamRefTypeTest extends TypeTest[TypeRepr, ParamRef]:
      def unapply(x: TypeRepr): Option[ParamRef & x.type] = x match
        case tpe: (Types.TypeParamRef & x.type) => Some(tpe)
        case tpe: (Types.TermParamRef & x.type) => Some(tpe)
        case _ => None
    end ParamRefTypeTest

    object ParamRef extends ParamRefModule:
      def unapply(x: ParamRef): (LambdaType, Int) =
        (x.binder, x.paramNum)
    end ParamRef

    given ParamRefMethods: ParamRefMethods with
      extension (self: ParamRef)
        def binder: LambdaType = self.binder.asInstanceOf[LambdaType] // Cast to tpd
        def paramNum: Int = self.paramNum
      end extension
    end ParamRefMethods

    type ThisType = dotc.core.Types.ThisType

    object ThisTypeTypeTest extends TypeTest[TypeRepr, ThisType]:
      def unapply(x: TypeRepr): Option[ThisType & x.type] = x match
        case tpe: (Types.ThisType & x.type) => Some(tpe)
        case _ => None
    end ThisTypeTypeTest

    object ThisType extends ThisTypeModule:
      def unapply(x: ThisType): Some[TypeRepr] = Some(x.tref)
    end ThisType

    given ThisTypeMethods: ThisTypeMethods with
      extension (self: ThisType)
        def tref: TypeRepr = self.tref
      end extension
    end ThisTypeMethods

    type RecursiveThis = dotc.core.Types.RecThis

    object RecursiveThisTypeTest extends TypeTest[TypeRepr, RecursiveThis]:
      def unapply(x: TypeRepr): Option[RecursiveThis & x.type] = x match
        case tpe: (Types.RecThis & x.type) => Some(tpe)
        case _ => None
    end RecursiveThisTypeTest

    object RecursiveThis extends RecursiveThisModule:
      def unapply(x: RecursiveThis): Some[RecursiveType] = Some(x.binder)
    end RecursiveThis


    given RecursiveThisMethods: RecursiveThisMethods with
      extension (self: RecursiveThis)
        def binder: RecursiveType = self.binder
      end extension
    end RecursiveThisMethods

    type RecursiveType = dotc.core.Types.RecType

    object RecursiveTypeTypeTest extends TypeTest[TypeRepr, RecursiveType]:
      def unapply(x: TypeRepr): Option[RecursiveType & x.type] = x match
        case tpe: (Types.RecType & x.type) => Some(tpe)
        case _ => None
    end RecursiveTypeTypeTest

    object RecursiveType extends RecursiveTypeModule:
      def apply(parentExp: RecursiveType => TypeRepr): RecursiveType =
        Types.RecType(parentExp)
      def unapply(x: RecursiveType): Some[TypeRepr] = Some(x.underlying)
    end RecursiveType

    given RecursiveTypeMethods: RecursiveTypeMethods with
      extension (self: RecursiveType)
        def underlying: TypeRepr = self.underlying.stripTypeVar
        def recThis: RecursiveThis = self.recThis
      end extension
    end RecursiveTypeMethods

    type LambdaType = dotc.core.Types.LambdaType

    object LambdaTypeTypeTest extends TypeTest[TypeRepr, LambdaType]:
      def unapply(x: TypeRepr): Option[LambdaType & x.type] = x match
        case tpe: (Types.LambdaType & x.type) => Some(tpe)
        case _ => None
    end LambdaTypeTypeTest

    given LambdaTypeMethods: LambdaTypeMethods with
      extension (self: LambdaType)
        def paramNames: List[String] = self.paramNames.map(_.toString)
        def paramTypes: List[TypeRepr] = self.paramInfos
        def resType: TypeRepr = self.resType
      end extension
    end LambdaTypeMethods

    type MethodOrPoly = dotc.core.Types.MethodOrPoly

    object MethodOrPolyTypeTest extends TypeTest[TypeRepr, MethodOrPoly]:
      def unapply(x: TypeRepr): Option[MethodOrPoly & x.type] = x match
        case tpe: (Types.MethodOrPoly & x.type) => Some(tpe)
        case _ => None
    end MethodOrPolyTypeTest

    type MethodType = dotc.core.Types.MethodType

    object MethodTypeTypeTest extends TypeTest[TypeRepr, MethodType]:
      def unapply(x: TypeRepr): Option[MethodType & x.type] = x match
        case tpe: (Types.MethodType & x.type) => Some(tpe)
        case _ => None
    end MethodTypeTypeTest

    object MethodType extends MethodTypeModule:
      def apply(paramNames: List[String])(paramInfosExp: MethodType => List[TypeRepr], resultTypeExp: MethodType => TypeRepr): MethodType =
        Types.MethodType(paramNames.map(_.toTermName))(paramInfosExp, resultTypeExp)
      def unapply(x: MethodType): (List[String], List[TypeRepr], TypeRepr) =
        (x.paramNames.map(_.toString), x.paramTypes, x.resType)
    end MethodType

    given MethodTypeMethods: MethodTypeMethods with
      extension (self: MethodType)
        def isErased: Boolean = self.isErasedMethod
        def isImplicit: Boolean = self.isImplicitMethod
        def param(idx: Int): TypeRepr = self.newParamRef(idx)
      end extension
    end MethodTypeMethods

    type PolyType = dotc.core.Types.PolyType

    object PolyTypeTypeTest extends TypeTest[TypeRepr, PolyType]:
      def unapply(x: TypeRepr): Option[PolyType & x.type] = x match
        case tpe: (Types.PolyType & x.type) => Some(tpe)
        case _ => None
    end PolyTypeTypeTest

    object PolyType extends PolyTypeModule:
      def apply(paramNames: List[String])(paramBoundsExp: PolyType => List[TypeBounds], resultTypeExp: PolyType => TypeRepr): PolyType =
        Types.PolyType(paramNames.map(_.toTypeName))(paramBoundsExp, resultTypeExp)
      def unapply(x: PolyType): (List[String], List[TypeBounds], TypeRepr) =
        (x.paramNames.map(_.toString), x.paramBounds, x.resType)
    end PolyType

    given PolyTypeMethods: PolyTypeMethods with
      extension (self: PolyType)
        def param(idx: Int): TypeRepr = self.newParamRef(idx)
        def paramBounds: List[TypeBounds] = self.paramInfos
      end extension
    end PolyTypeMethods

    type TypeLambda = dotc.core.Types.TypeLambda

    object TypeLambdaTypeTest extends TypeTest[TypeRepr, TypeLambda]:
      def unapply(x: TypeRepr): Option[TypeLambda & x.type] = x match
        case tpe: (Types.TypeLambda & x.type) => Some(tpe)
        case _ => None
    end TypeLambdaTypeTest

    object TypeLambda extends TypeLambdaModule:
      def apply(paramNames: List[String], boundsFn: TypeLambda => List[TypeBounds], bodyFn: TypeLambda => TypeRepr): TypeLambda =
        Types.HKTypeLambda(paramNames.map(_.toTypeName))(boundsFn, bodyFn)
      def unapply(x: TypeLambda): (List[String], List[TypeBounds], TypeRepr) =
        (x.paramNames.map(_.toString), x.paramBounds, x.resType)
    end TypeLambda

    given TypeLambdaMethods: TypeLambdaMethods with
      extension (self: TypeLambda)
        def param(idx: Int): TypeRepr = self.newParamRef(idx)
        def paramBounds: List[TypeBounds] = self.paramInfos
      end extension
    end TypeLambdaMethods

    type TypeBounds = dotc.core.Types.TypeBounds

    object TypeBoundsTypeTest extends TypeTest[TypeRepr, TypeBounds]:
      def unapply(x: TypeRepr): Option[TypeBounds & x.type] = x match
        case x: (Types.TypeBounds & x.type) => Some(x)
        case _ => None
    end TypeBoundsTypeTest

    object TypeBounds extends TypeBoundsModule:
      def apply(low: TypeRepr, hi: TypeRepr): TypeBounds = Types.TypeBounds(low, hi)
      def unapply(x: TypeBounds): (TypeRepr, TypeRepr) = (x.low.stripLazyRef, x.hi.stripLazyRef)
      def empty: TypeBounds = Types .TypeBounds.empty
      def upper(hi: TypeRepr): TypeBounds = Types .TypeBounds.upper(hi)
      def lower(lo: TypeRepr): TypeBounds = Types .TypeBounds.lower(lo)
    end TypeBounds

    given TypeBoundsMethods: TypeBoundsMethods with
      extension (self: TypeBounds)
        def low: TypeRepr = self.lo.stripLazyRef
        def hi: TypeRepr = self.hi.stripLazyRef
      end extension
    end TypeBoundsMethods

    type NoPrefix = dotc.core.Types.NoPrefix.type

    object NoPrefixTypeTest extends TypeTest[TypeRepr, NoPrefix]:
      def unapply(x: TypeRepr): Option[NoPrefix & x.type] =
        if x == Types.NoPrefix then Some(x.asInstanceOf[NoPrefix & x.type]) else None
    end NoPrefixTypeTest

    object NoPrefix extends NoPrefixModule:
      def unapply(x: NoPrefix): true = true
    end NoPrefix

    type Constant = dotc.core.Constants.Constant

    object Constant extends ConstantModule:

      object Boolean extends BooleanModule:
        def apply(x: Boolean): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[Boolean] =
          if constant.tag == dotc.core.Constants.BooleanTag then Some(constant.booleanValue)
          else None
      end Boolean

      object Byte extends ByteModule:
        def apply(x: Byte): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[Byte] =
          if constant.tag == dotc.core.Constants.ByteTag then Some(constant.byteValue)
          else None
      end Byte

      object Short extends ShortModule:
        def apply(x: Short): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[Short] =
          if constant.tag == dotc.core.Constants.ShortTag then Some(constant.shortValue)
          else None
      end Short

      object Int extends IntModule:
        def apply(x: Int): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[Int] =
          if constant.tag == dotc.core.Constants.IntTag then Some(constant.intValue)
          else None
      end Int

      object Long extends LongModule:
        def apply(x: Long): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[Long] =
          if constant.tag == dotc.core.Constants.LongTag then Some(constant.longValue)
          else None
      end Long

      object Float extends FloatModule:
        def apply(x: Float): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[Float] =
          if constant.tag == dotc.core.Constants.FloatTag then Some(constant.floatValue)
          else None
      end Float

      object Double extends DoubleModule:
        def apply(x: Double): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[Double] =
          if constant.tag == dotc.core.Constants.DoubleTag then Some(constant.doubleValue)
          else None
      end Double

      object Char extends CharModule:
        def apply(x: Char): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[Char] =
          if constant.tag == dotc.core.Constants.CharTag then Some(constant.charValue)
          else None
      end Char

      object String extends StringModule:
        def apply(x: String): Constant = dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[String] =
          if constant.tag == dotc.core.Constants.StringTag then Some(constant.stringValue)
          else None
      end String

      object Unit extends UnitModule:
        def apply(): Constant = dotc.core.Constants.Constant(())
        def unapply(constant: Constant): Boolean =
          constant.tag == dotc.core.Constants.UnitTag
      end Unit

      object Null extends NullModule:
        def apply(): Constant = dotc.core.Constants.Constant(null)
        def unapply(constant: Constant): Boolean =
          constant.tag == dotc.core.Constants.NullTag
      end Null

      object ClassOf extends ClassOfModule:
        def apply(x: TypeRepr): Constant =
          // TODO check that the type is a valid class when creating this constant or let Ycheck do it?
          dotc.core.Constants.Constant(x)
        def unapply(constant: Constant): Option[TypeRepr] =
          if constant.tag == dotc.core.Constants.ClazzTag then Some(constant.typeValue)
          else None
      end ClassOf

    end Constant

    given ConstantMethods: ConstantMethods with
      extension (self: Constant)
        def value: Any = self.value
        def show: String = Extractors.showConstant(using QuotesImpl.this)(self)
      end extension
    end ConstantMethods

    object Implicits extends ImplicitsModule:
      def search(tpe: TypeRepr): ImplicitSearchResult =
        ctx.typer.inferImplicitArg(tpe, Position.ofMacroExpansion.span)
    end Implicits

    type ImplicitSearchResult = Tree

    type ImplicitSearchSuccess = Tree

    object ImplicitSearchSuccessTypeTest extends TypeTest[ImplicitSearchResult, ImplicitSearchSuccess]:
      def unapply(x: ImplicitSearchResult): Option[ImplicitSearchSuccess & x.type] =
        x.tpe match
          case _: dotc.typer.Implicits.SearchFailureType => None
          case _ => Some(x)
    end ImplicitSearchSuccessTypeTest

    given ImplicitSearchSuccessMethods: ImplicitSearchSuccessMethods with
      extension (self: ImplicitSearchSuccess)
        def tree: Term = self
      end extension
    end ImplicitSearchSuccessMethods

    type ImplicitSearchFailure = Tree

    object ImplicitSearchFailureTypeTest extends TypeTest[ImplicitSearchResult, ImplicitSearchFailure]:
      def unapply(x: ImplicitSearchResult): Option[ImplicitSearchFailure & x.type] =
        x.tpe match
          case _: dotc.typer.Implicits.SearchFailureType => Some(x)
          case _ => None
    end ImplicitSearchFailureTypeTest

    given ImplicitSearchFailureMethods: ImplicitSearchFailureMethods with
      extension (self: ImplicitSearchFailure)
        def explanation: String =
          self.tpe.asInstanceOf[dotc.typer.Implicits.SearchFailureType].explanation
      end extension
    end ImplicitSearchFailureMethods

    type DivergingImplicit = Tree

    object DivergingImplicitTypeTest extends TypeTest[ImplicitSearchResult, DivergingImplicit]:
      def unapply(x: ImplicitSearchResult): Option[DivergingImplicit & x.type] =
        x.tpe match
          case _: dotc.typer.Implicits.DivergingImplicit => Some(x)
          case _ => None
    end DivergingImplicitTypeTest

    type NoMatchingImplicits = Tree

    object NoMatchingImplicitsTypeTest extends TypeTest[ImplicitSearchResult, NoMatchingImplicits]:
      def unapply(x: ImplicitSearchResult): Option[NoMatchingImplicits & x.type] =
        x.tpe match
          case _: dotc.typer.Implicits.NoMatchingImplicits => Some(x)
          case _ => None
    end NoMatchingImplicitsTypeTest

    type AmbiguousImplicits = Tree

    object AmbiguousImplicitsTypeTest extends TypeTest[ImplicitSearchResult, AmbiguousImplicits]:
      def unapply(x: ImplicitSearchResult): Option[AmbiguousImplicits & x.type] =
        x.tpe match
          case _: dotc.typer.Implicits.AmbiguousImplicits => Some(x)
          case _ => None
    end AmbiguousImplicitsTypeTest

    type Symbol = dotc.core.Symbols.Symbol

    object Symbol extends SymbolModule:
      def spliceOwner: Symbol = ctx.owner
      def requiredPackage(path: String): Symbol = dotc.core.Symbols.requiredPackage(path)
      def requiredClass(path: String): Symbol = dotc.core.Symbols.requiredClass(path)
      def requiredModule(path: String): Symbol = dotc.core.Symbols.requiredModule(path)
      def requiredMethod(path: String): Symbol = dotc.core.Symbols.requiredMethod(path)
      def classSymbol(fullName: String): Symbol = dotc.core.Symbols.requiredClass(fullName)
      def newMethod(owner: Symbol, name: String, tpe: TypeRepr): Symbol =
        newMethod(owner, name, tpe, Flags.EmptyFlags, noSymbol)
      def newMethod(owner: Symbol, name: String, tpe: TypeRepr, flags: Flags, privateWithin: Symbol): Symbol =
        dotc.core.Symbols.newSymbol(owner, name.toTermName, flags | dotc.core.Flags.Method, tpe, privateWithin)
      def newVal(owner: Symbol, name: String, tpe: TypeRepr, flags: Flags, privateWithin: Symbol): Symbol =
        dotc.core.Symbols.newSymbol(owner, name.toTermName, flags, tpe, privateWithin)
      def newBind(owner: Symbol, name: String, flags: Flags, tpe: TypeRepr): Symbol =
        dotc.core.Symbols.newSymbol(owner, name.toTermName, flags | Case, tpe)
      def noSymbol: Symbol = dotc.core.Symbols.NoSymbol
    end Symbol

    given SymbolMethods: SymbolMethods with
      extension (self: Symbol)
        def owner: Symbol = self.denot.owner
        def maybeOwner: Symbol = self.denot.maybeOwner
        def flags: Flags = self.denot.flags

        def privateWithin: Option[TypeRepr] =
          val within: Symbol =
          self.denot.privateWithin
          if (within.exists && !self.is(dotc.core.Flags.Protected)) Some(within.typeRef)
          else None

        def protectedWithin: Option[TypeRepr] =
          val within: Symbol =
          self.denot.privateWithin
          if (within.exists && self.is(dotc.core.Flags.Protected)) Some(within.typeRef)
          else None

        def name: String = self.denot.name.toString
        def fullName: String = self.denot.fullName.toString
        def pos: Option[Position] =
          if self.exists then Some(self.sourcePos) else None

        def docstring: Option[String] =
          import dotc.core.Comments.CommentsContext
          val docCtx = ctx.docCtx.getOrElse {
            throw new RuntimeException(
              "DocCtx could not be found and documentations are unavailable. This is a compiler-internal error."
            )
          }
          docCtx.docstring(self).map(_.raw)

        def tree: Tree = FromSymbol.definitionFromSym(self)

        def hasAnnotation(annotSym: Symbol): Boolean =
          self.denot.hasAnnotation(annotSym)

        def getAnnotation(annotSym: Symbol): Option[Term] =
          self.denot.getAnnotation(annotSym).map(_.tree)

        def annotations: List[Term] =
          self.denot.annotations.flatMap {
            case _: dotc.core.Annotations.BodyAnnotation => Nil
            case annot => annot.tree :: Nil
          }

        def isDefinedInCurrentRun: Boolean =
          self.topLevelClass.asClass.isDefinedInCurrentRun
        def isLocalDummy: Boolean = self.denot.isLocalDummy
        def isRefinementClass: Boolean = self.denot.isRefinementClass
        def isAliasType: Boolean = self.denot.isAliasType
        def isAnonymousClass: Boolean = self.denot.isAnonymousClass
        def isAnonymousFunction: Boolean = self.denot.isAnonymousFunction
        def isAbstractType: Boolean = self.denot.isAbstractType
        def isClassConstructor: Boolean = self.denot.isClassConstructor
        def isType: Boolean = self.isType
        def isTerm: Boolean = self.isTerm
        def isPackageDef: Boolean = self.is(dotc.core.Flags.Package)
        def isClassDef: Boolean = self.isClass
        def isTypeDef: Boolean =
          self.isType && !self.isClass && !self.is(dotc.core.Flags.Case)
        def isValDef: Boolean =
          self.isTerm && !self.is(dotc.core.Flags.Method) && !self.is(dotc.core.Flags.Case/*, FIXME add this check and fix sourcecode butNot = Enum | Module*/)
        def isDefDef: Boolean = self.is(dotc.core.Flags.Method)
        def isBind: Boolean =
          self.is(dotc.core.Flags.Case, butNot = Enum | Module) && !self.isClass
        def isNoSymbol: Boolean = self == Symbol.noSymbol
        def exists: Boolean = self != Symbol.noSymbol

        def declaredField(name: String): Symbol =
          val sym = self.unforcedDecls.find(sym => sym.name == name.toTermName)
          if (isField(sym)) sym else dotc.core.Symbols.NoSymbol

        def declaredFields: List[Symbol] = self.unforcedDecls.filter(isField)

        def memberField(name: String): Symbol =
          appliedTypeRef(self).allMembers.iterator.map(_.symbol).find {
            sym => isField(sym) && sym.name.toString == name
          }.getOrElse(dotc.core.Symbols.NoSymbol)

        def memberFields: List[Symbol] =
          appliedTypeRef(self).allMembers.iterator.map(_.symbol).collect {
            case sym if isField(sym) => sym.asTerm
          }.toList

        def declaredMethod(name: String): List[Symbol] =
          self.typeRef.decls.iterator.collect {
            case sym if isMethod(sym) && sym.name.toString == name => sym.asTerm
          }.toList

        def declaredMethods: List[Symbol] =
          self.typeRef.decls.iterator.collect {
            case sym if isMethod(sym) => sym.asTerm
          }.toList

        def memberMethod(name: String): List[Symbol] =
          appliedTypeRef(self).allMembers.iterator.map(_.symbol).collect {
            case sym if isMethod(sym) && sym.name.toString == name => sym.asTerm
          }.toList

        def memberMethods: List[Symbol] =
          appliedTypeRef(self).allMembers.iterator.map(_.symbol).collect {
            case sym if isMethod(sym) => sym.asTerm
          }.toList

        def declaredType(name: String): List[Symbol] =
          self.typeRef.decls.iterator.collect {
            case sym if sym.isType && sym.name.toString == name => sym.asType
          }.toList

        def declaredTypes: List[Symbol] =
          self.typeRef.decls.iterator.collect {
            case sym if sym.isType => sym.asType
          }.toList

        def memberType(name: String): Symbol =
          self.unforcedDecls.find(sym => sym.name == name.toTypeName)

        def memberTypes: List[Symbol] =
          self.unforcedDecls.filter(_.isType)

        def declarations: List[Symbol] =
          self.typeRef.info.decls.toList

        def paramSymss: List[List[Symbol]] = self.denot.paramSymss
        def primaryConstructor: Symbol = self.denot.primaryConstructor
        def allOverriddenSymbols: Iterator[Symbol] = self.denot.allOverriddenSymbols
        def overridingSymbol(ofclazz: Symbol): Symbol =
          if ofclazz.isClass then self.denot.overridingSymbol(ofclazz.asClass)
          else dotc.core.Symbols.NoSymbol

        def caseFields: List[Symbol] =
          if !self.isClass then Nil
          else self.asClass.paramAccessors.collect {
            case sym if sym.is(dotc.core.Flags.CaseAccessor) => sym.asTerm
          }

        def isTypeParam: Boolean = self.isTypeParam
        def signature: Signature = self.signature
        def moduleClass: Symbol = self.denot.moduleClass
        def companionClass: Symbol = self.denot.companionClass
        def companionModule: Symbol = self.denot.companionModule
        def children: List[Symbol] = self.denot.children

        def show(using printer: Printer[Symbol]): String = printer.show(self)

      end extension

      private def appliedTypeRef(sym: Symbol): TypeRepr =
        sym.typeRef.appliedTo(sym.typeParams.map(_.typeRef))

      private def isMethod(sym: Symbol): Boolean =
        sym.isTerm && sym.is(dotc.core.Flags.Method) && !sym.isConstructor

      private def isField(sym: Symbol): Boolean =
        sym.isTerm && !sym.is(dotc.core.Flags.Method)
    end SymbolMethods

    type Signature = dotc.core.Signature

    object Signature extends SignatureModule:
      def unapply(sig: Signature): (List[String | Int], String) =
        (sig.paramSigs, sig.resultSig)
    end Signature

    given SignatureMethods: SignatureMethods with
      extension (self: Signature)
        def paramSigs: List[String | Int] =
          self.paramsSig.map {
            case paramSig: dotc.core.Names.TypeName =>
              paramSig.toString
            case paramSig: Int =>
              paramSig
          }
        def resultSig: String =
          self.resSig.toString
      end extension
    end SignatureMethods

    object defn extends defnModule:
      def RootPackage: Symbol = dotc.core.Symbols.defn.RootPackage
      def RootClass: Symbol = dotc.core.Symbols.defn.RootClass
      def EmptyPackageClass: Symbol = dotc.core.Symbols.defn.EmptyPackageClass
      def ScalaPackage: Symbol = dotc.core.Symbols.defn.ScalaPackageVal
      def ScalaPackageClass: Symbol = dotc.core.Symbols.defn.ScalaPackageClass
      def AnyClass: Symbol = dotc.core.Symbols.defn.AnyClass
      def AnyValClass: Symbol = dotc.core.Symbols.defn.AnyValClass
      def ObjectClass: Symbol = dotc.core.Symbols.defn.ObjectClass
      def AnyRefClass: Symbol = dotc.core.Symbols.defn.AnyRefAlias
      def NullClass: Symbol = dotc.core.Symbols.defn.NullClass
      def NothingClass: Symbol = dotc.core.Symbols.defn.NothingClass
      def UnitClass: Symbol = dotc.core.Symbols.defn.UnitClass
      def ByteClass: Symbol = dotc.core.Symbols.defn.ByteClass
      def ShortClass: Symbol = dotc.core.Symbols.defn.ShortClass
      def CharClass: Symbol = dotc.core.Symbols.defn.CharClass
      def IntClass: Symbol = dotc.core.Symbols.defn.IntClass
      def LongClass: Symbol = dotc.core.Symbols.defn.LongClass
      def FloatClass: Symbol = dotc.core.Symbols.defn.FloatClass
      def DoubleClass: Symbol = dotc.core.Symbols.defn.DoubleClass
      def BooleanClass: Symbol = dotc.core.Symbols.defn.BooleanClass
      def StringClass: Symbol = dotc.core.Symbols.defn.StringClass
      def ClassClass: Symbol = dotc.core.Symbols.defn.ClassClass
      def ArrayClass: Symbol = dotc.core.Symbols.defn.ArrayClass
      def PredefModule: Symbol = dotc.core.Symbols.defn.ScalaPredefModule
      def Predef_classOf: Symbol = dotc.core.Symbols.defn.Predef_classOf
      def JavaLangPackage: Symbol = dotc.core.Symbols.defn.JavaLangPackageVal
      def ArrayModule: Symbol = dotc.core.Symbols.defn.ArrayModule
      def Array_apply: Symbol = dotc.core.Symbols.defn.Array_apply
      def Array_clone: Symbol = dotc.core.Symbols.defn.Array_clone
      def Array_length: Symbol = dotc.core.Symbols.defn.Array_length
      def Array_update: Symbol = dotc.core.Symbols.defn.Array_update
      def RepeatedParamClass: Symbol = dotc.core.Symbols.defn.RepeatedParamClass
      def RepeatedAnnot: Symbol = dotc.core.Symbols.defn.RepeatedAnnot
      def OptionClass: Symbol = dotc.core.Symbols.defn.OptionClass
      def NoneModule: Symbol = dotc.core.Symbols.defn.NoneModule
      def SomeModule: Symbol = dotc.core.Symbols.defn.SomeClass.companionModule
      def ProductClass: Symbol = dotc.core.Symbols.defn.ProductClass
      def FunctionClass(arity: Int, isImplicit: Boolean = false, isErased: Boolean = false): Symbol =
        dotc.core.Symbols.defn.FunctionClass(arity, isImplicit, isErased)
      def TupleClass(arity: Int): Symbol =
        dotc.core.Symbols.defn.TupleType(arity).classSymbol.asClass
      def isTupleClass(sym: Symbol): Boolean =
        dotc.core.Symbols.defn.isTupleClass(sym)
      def ScalaPrimitiveValueClasses: List[Symbol] =
        UnitClass :: BooleanClass :: ScalaNumericValueClasses
      def ScalaNumericValueClasses: List[Symbol] =
        ByteClass :: ShortClass :: IntClass :: LongClass :: FloatClass :: DoubleClass :: CharClass :: Nil
    end defn

    type Flags = dotc.core.Flags.FlagSet

    object Flags extends FlagsModule:
      def Abstract: Flags = dotc.core.Flags.Abstract
      def Artifact: Flags = dotc.core.Flags.Artifact
      def Case: Flags = dotc.core.Flags.Case
      def CaseAccessor: Flags = dotc.core.Flags.CaseAccessor
      def Contravariant: Flags = dotc.core.Flags.Contravariant
      def Covariant: Flags = dotc.core.Flags.Covariant
      def Deferred: Flags = dotc.core.Flags.Deferred
      def EmptyFlags = dotc.core.Flags.EmptyFlags
      def Enum: Flags = dotc.core.Flags.Enum
      def Erased: Flags = dotc.core.Flags.Erased
      def Exported: Flags = dotc.core.Flags.Exported
      def ExtensionMethod: Flags = dotc.core.Flags.ExtensionMethod
      def FieldAccessor: Flags = dotc.core.Flags.Accessor
      def Final: Flags = dotc.core.Flags.Final
      def Given: Flags = dotc.core.Flags.Given
      def HasDefault: Flags = dotc.core.Flags.HasDefault
      def Implicit: Flags = dotc.core.Flags.Implicit
      def Infix: Flags = dotc.core.Flags.Infix
      def Inline: Flags = dotc.core.Flags.Inline
      def JavaDefined: Flags = dotc.core.Flags.JavaDefined
      def JavaStatic: Flags = dotc.core.Flags.JavaStatic
      def Lazy: Flags = dotc.core.Flags.Lazy
      def Local: Flags = dotc.core.Flags.Local
      def Macro: Flags = dotc.core.Flags.Macro
      def Method: Flags = dotc.core.Flags.Method
      def Module: Flags = dotc.core.Flags.Module
      def Mutable: Flags = dotc.core.Flags.Mutable
      def NoInits: Flags = dotc.core.Flags.NoInits
      def Opaque: Flags = dotc.core.Flags.Opaque
      def Open: Flags = dotc.core.Flags.Open
      def Override: Flags = dotc.core.Flags.Override
      def Package: Flags = dotc.core.Flags.Package
      def Param: Flags = dotc.core.Flags.Param
      def ParamAccessor: Flags = dotc.core.Flags.ParamAccessor
      def Private: Flags = dotc.core.Flags.Private
      def PrivateLocal: Flags = dotc.core.Flags.PrivateLocal
      def Protected: Flags = dotc.core.Flags.Protected
      def Scala2x: Flags = dotc.core.Flags.Scala2x
      def Sealed: Flags = dotc.core.Flags.Sealed
      def StableRealizable: Flags = dotc.core.Flags.StableRealizable
      def Static: Flags = dotc.core.Flags.JavaStatic
      def Synthetic: Flags = dotc.core.Flags.Synthetic
      def Trait: Flags = dotc.core.Flags.Trait
      def Transparent: Flags = dotc.core.Flags.Transparent
    end Flags

    given FlagsMethods: FlagsMethods with
      extension (self: Flags)
        def is(that: Flags): Boolean = self.isAllOf(that)
        def |(that: Flags): Flags = dotc.core.Flags.or(self, that) // TODO: Replace with dotc.core.Flags.|(self)(that)  once extension names have stabilized
        def &(that: Flags): Flags = dotc.core.Flags.and(self, that)// TODO: Replace with dotc.core.Flags.&(self)(that)  once extension names have stabilized
        def show: String = Extractors.showFlags(using QuotesImpl.this)(self)
      end extension
    end FlagsMethods

    type Position = dotc.util.SourcePosition

    object Position extends PositionModule:
      def ofMacroExpansion: dotc.util.SourcePosition =
        MacroExpansion.position.getOrElse(dotc.util.SourcePosition(ctx.source, dotc.util.Spans.NoSpan))
      def apply(sourceFile: SourceFile, start: Int, end: Int): Position =
        dotc.util.SourcePosition(sourceFile, dotc.util.Spans.Span(start, end))
    end Position

    given PositionMethods: PositionMethods with
      extension (self: Position)
        def start: Int = self.start
        def end: Int = self.end
        def sourceFile: SourceFile = self.source
        def startLine: Int = self.startLine
        def endLine: Int = self.endLine
        def startColumn: Int = self.startColumn
        def endColumn: Int = self.endColumn
        def sourceCode: Option[String] =
          // TODO detect when we do not have a source and return None
          Some(new String(self.source.content(), self.start, self.end - self.start))
      end extension
    end PositionMethods

    type SourceFile = dotc.util.SourceFile

    object SourceFile extends SourceFileModule {
      def current: SourceFile = ctx.compilationUnit.source
    }

    given SourceFileMethods: SourceFileMethods with
      extension (self: SourceFile)
        def jpath: java.nio.file.Path = self.file.jpath
        def content: Option[String] =
          // TODO detect when we do not have a source and return None
          Some(new String(self.content()))
      end extension
    end SourceFileMethods

    object report extends reportModule:

      def error(msg: String): Unit =
        dotc.report.error(msg, Position.ofMacroExpansion)

      def error(msg: String, expr: Expr[Any]): Unit =
        dotc.report.error(msg, asTerm(expr).pos)

      def error(msg: String, pos: Position): Unit =
        dotc.report.error(msg, pos)

      def throwError(msg: String): Nothing =
        error(msg)
        throw new scala.quoted.runtime.StopMacroExpansion

      def throwError(msg: String, expr: Expr[Any]): Nothing =
        error(msg, expr)
        throw new scala.quoted.runtime.StopMacroExpansion

      def throwError(msg: String, pos: Position): Nothing =
        error(msg, pos)
        throw new scala.quoted.runtime.StopMacroExpansion

      def warning(msg: String): Unit =
        dotc.report.warning(msg, Position.ofMacroExpansion)

      def warning(msg: String, expr: Expr[Any]): Unit =
        dotc.report.warning(msg, asTerm(expr).pos)

      def warning(msg: String, pos: Position): Unit =
        dotc.report.warning(msg, pos)

    end report

    private def optional[T <: dotc.ast.Trees.Tree[?]](tree: T): Option[tree.type] =
      if tree.isEmpty then None else Some(tree)

    private def withDefaultPos[T <: Tree](fn: Context ?=> T): T =
      fn(using ctx.withSource(Position.ofMacroExpansion.source)).withSpan(Position.ofMacroExpansion.span)

    /** Checks that all definitions in this tree have the expected owner.
     *  Nested definitions are ignored and assumed to be correct by construction.
     */
    private def yCheckedOwners(tree: Option[Tree], owner: Symbol): tree.type =
      if yCheck then
        tree match
          case Some(tree) =>
            yCheckOwners(tree, owner)
          case _ =>
      tree

    /** Checks that all definitions in this tree have the expected owner.
     *  Nested definitions are ignored and assumed to be correct by construction.
     */
    private def yCheckedOwners(tree: Tree, owner: Symbol): tree.type =
      if yCheck then
        yCheckOwners(tree, owner)
      tree

    /** Checks that all definitions in this tree have the expected owner.
     *  Nested definitions are ignored and assumed to be correct by construction.
     */
    private def yCheckOwners(tree: Tree, owner: Symbol): Unit =
      new tpd.TreeTraverser {
        def traverse(t: Tree)(using Context): Unit =
          t match
            case t: tpd.DefTree =>
              val defOwner = t.symbol.owner
              assert(defOwner == owner,
                s"""Tree had an unexpected owner for ${t.symbol}
                   |Expected: $owner (${owner.fullName})
                   |But was: $defOwner (${defOwner.fullName})
                   |
                   |
                   |The code of the definition of ${t.symbol} is
                   |${Printer.TreeCode.show(t)}
                   |
                   |which was found in the code
                   |${Printer.TreeCode.show(tree)}
                   |
                   |which has the AST representation
                   |${Printer.TreeStructure.show(tree)}
                   |
                   |""".stripMargin)
            case _ => traverseChildren(t)
      }.traverse(tree)

    object Printer extends PrinterModule:

      lazy val TreeCode: Printer[Tree] = new Printer[Tree]:
        def show(tree: Tree): String =
          SourceCode.showTree(using QuotesImpl.this)(tree)(SyntaxHighlight.plain, fullNames = true)

      lazy val TreeShortCode: Printer[Tree] = new Printer[Tree]:
        def show(tree: Tree): String =
          SourceCode.showTree(using QuotesImpl.this)(tree)(SyntaxHighlight.plain, fullNames = false)

      lazy val TreeAnsiCode: Printer[Tree] = new Printer[Tree]:
        def show(tree: Tree): String =
          SourceCode.showTree(using QuotesImpl.this)(tree)(SyntaxHighlight.ANSI, fullNames = true)

      lazy val TreeStructure: Printer[Tree] = new Printer[Tree]:
        def show(tree: Tree): String =
          Extractors.showTree(using QuotesImpl.this)(tree)

      lazy val TypeReprCode: Printer[TypeRepr] = new Printer[TypeRepr]:
        def show(tpe: TypeRepr): String =
          SourceCode.showType(using QuotesImpl.this)(tpe)(SyntaxHighlight.plain, fullNames = true)

      lazy val TypeReprShortCode: Printer[TypeRepr] = new Printer[TypeRepr]:
        def show(tpe: TypeRepr): String =
          SourceCode.showType(using QuotesImpl.this)(tpe)(SyntaxHighlight.plain, fullNames = false)

      lazy val TypeReprAnsiCode: Printer[TypeRepr] = new Printer[TypeRepr]:
        def show(tpe: TypeRepr): String =
          SourceCode.showType(using QuotesImpl.this)(tpe)(SyntaxHighlight.ANSI, fullNames = true)

      lazy val TypeReprStructure: Printer[TypeRepr] = new Printer[TypeRepr]:
        def show(tpe: TypeRepr): String =
          Extractors.showType(using QuotesImpl.this)(tpe)

    end Printer
  end reflect

  def unpickleExpr[T](pickled: String | List[String], typeHole: (Int, Seq[Any]) => scala.quoted.Type[?], termHole: (Int, Seq[Any], scala.quoted.Quotes) => scala.quoted.Expr[?]): scala.quoted.Expr[T] =
    val tree = PickledQuotes.unpickleTerm(pickled, typeHole, termHole)
    new ExprImpl(tree, hash).asInstanceOf[scala.quoted.Expr[T]]

  def unpickleType[T <: AnyKind](pickled: String | List[String], typeHole: (Int, Seq[Any]) => scala.quoted.Type[?], termHole: (Int, Seq[Any], scala.quoted.Quotes) => scala.quoted.Expr[?]): scala.quoted.Type[T] =
    val tree = PickledQuotes.unpickleTypeTree(pickled, typeHole, termHole)
    new TypeImpl(tree, hash).asInstanceOf[scala.quoted.Type[T]]

  object ExprMatch extends ExprMatchModule:
    def unapply[TypeBindings <: Tuple, Tup <: Tuple](scrutinee: scala.quoted.Expr[Any])(using pattern: scala.quoted.Expr[Any]): Option[Tup] =
      val scrutineeTree = reflect.asTerm(scrutinee)
      val patternTree = reflect.asTerm(pattern)
      treeMatch(scrutineeTree, patternTree).asInstanceOf[Option[Tup]]
  end ExprMatch

  object TypeMatch extends TypeMatchModule:
    def unapply[TypeBindings <: Tuple, Tup <: Tuple](scrutinee: scala.quoted.Type[?])(using pattern: scala.quoted.Type[?]): Option[Tup] =
      val scrutineeTree = reflect.TypeTree.of(using scrutinee)
      val patternTree = reflect.TypeTree.of(using pattern)
      treeMatch(scrutineeTree, patternTree).asInstanceOf[Option[Tup]]
  end TypeMatch

  private def treeMatch(scrutinee: reflect.Tree, pattern: reflect.Tree): Option[Tuple] = {
    import reflect._
    def isTypeHoleDef(tree: Tree): Boolean =
      tree match
        case tree: TypeDef =>
          tree.symbol.hasAnnotation(dotc.core.Symbols.defn.QuotedRuntimePatterns_patternTypeAnnot)
        case _ => false

    def extractTypeHoles(pat: Term): (Term, List[Symbol]) =
      pat match
        case tpd.Inlined(_, Nil, pat2) => extractTypeHoles(pat2)
        case tpd.Block(stats @ ((typeHole: TypeDef) :: _), expr) if isTypeHoleDef(typeHole) =>
          val holes = stats.takeWhile(isTypeHoleDef).map(_.symbol)
          val otherStats = stats.dropWhile(isTypeHoleDef)
          (tpd.cpy.Block(pat)(otherStats, expr), holes)
        case _ =>
          (pat, Nil)

    val (pat1, typeHoles) = extractTypeHoles(pattern)

    val ctx1 =
      if typeHoles.isEmpty then ctx
      else
        val ctx1 = ctx.fresh.setFreshGADTBounds.addMode(dotc.core.Mode.GadtConstraintInference)
        ctx1.gadt.addToConstraint(typeHoles)
        ctx1

    val qctx1 = QuotesImpl()(using ctx1)

    val matcher = new Matcher.QuoteMatcher[qctx1.type](qctx1) {
      def patternHoleSymbol: qctx1.reflect.Symbol = dotc.core.Symbols.defn.QuotedRuntimePatterns_patternHole.asInstanceOf
      def higherOrderHoleSymbol: qctx1.reflect.Symbol = dotc.core.Symbols.defn.QuotedRuntimePatterns_higherOrderHole.asInstanceOf
    }

    val matchings =
      if pat1.isType then matcher.termMatch(scrutinee.asInstanceOf[matcher.qctx.reflect.Term], pat1.asInstanceOf[matcher.qctx.reflect.Term])
      else matcher.termMatch(scrutinee.asInstanceOf[matcher.qctx.reflect.Term], pat1.asInstanceOf[matcher.qctx.reflect.Term])

    // val matchings = matcher.termMatch(scrutinee, pattern)
    if typeHoles.isEmpty then matchings
    else {
      // After matching and doing all subtype checks, we have to approximate all the type bindings
      // that we have found, seal them in a quoted.Type and add them to the result
      def typeHoleApproximation(sym: Symbol) =
        ctx1.gadt.approximation(sym, !sym.hasAnnotation(dotc.core.Symbols.defn.QuotedRuntimePatterns_fromAboveAnnot)).asInstanceOf[qctx1.reflect.TypeRepr].asType
      matchings.map { tup =>
        Tuple.fromIArray(typeHoles.map(typeHoleApproximation).toArray.asInstanceOf[IArray[Object]]) ++ tup
      }
    }
  }

  private[this] val hash = QuotesImpl.scopeId(using ctx)
  override def hashCode: Int = hash

end QuotesImpl
