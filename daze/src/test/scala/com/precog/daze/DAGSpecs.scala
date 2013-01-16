/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package daze

import org.specs2.mutable._
import bytecode._
import com.precog.yggdrasil._

object DAGSpecs extends Specification with DAG with RandomLibrary with FNDummyModule {
  import instructions._
  import dag._
  
  "dag decoration" should {
    "recognize root instructions" in {
      "push_str" >> {
        decorate(Vector(Line(0, ""), PushString("test"))) mustEqual Right(Const(CString("test"))(Line(0, "")))
      }
      
      "push_num" >> {
        decorate(Vector(Line(0, ""), PushNum("42"))) mustEqual Right(Const(CLong(42))(Line(0, "")))
      }
      
      "push_true" >> {
        decorate(Vector(Line(0, ""), PushTrue)) mustEqual Right(Const(CBoolean(true))(Line(0, "")))
      }
      
      "push_false" >> {
        decorate(Vector(Line(0, ""), PushFalse)) mustEqual Right(Const(CBoolean(false))(Line(0, "")))
      }      

      "push_null" >> {
        decorate(Vector(Line(0, ""), PushNull)) mustEqual Right(Const(CNull)(Line(0, "")))
      }
      
      "push_object" >> {
        decorate(Vector(Line(0, ""), PushObject)) mustEqual Right(Const(CEmptyObject)(Line(0, "")))
      }
      
      "push_array" >> {
        decorate(Vector(Line(0, ""), PushArray)) mustEqual Right(Const(CEmptyArray)(Line(0, "")))
      }

      "push_undefined" >> {
        decorate(Vector(Line(0, ""), PushUndefined)) mustEqual Right(Undefined(Line(0, "")))
      }
    }
    
    "recognize a new instruction" in {
      decorate(Vector(Line(0, ""), PushNum("5"), Map1(instructions.New))) mustEqual Right(dag.New(Const(CLong(5))(Line(0, "")))(Line(0, "")))
    }
    
    "parse out load_local" in {
      val result = decorate(Vector(Line(0, ""), PushString("/foo"), instructions.LoadLocal))
      result mustEqual Right(dag.LoadLocal(Const(CString("/foo"))(Line(0, "")))(Line(0, "")))
    }
    
    "parse out map1" in {
      val result = decorate(Vector(Line(0, ""), PushTrue, Map1(Neg)))
      result mustEqual Right(Operate(Neg, Const(CBoolean(true))(Line(0, "")))(Line(0, "")))
    }
    
    "parse out reduce" in {
      val result = decorate(Vector(Line(0, ""), PushFalse, instructions.Reduce(BuiltInReduction(Reduction(Vector(), "count", 0x2000)))))
      result mustEqual Right(dag.Reduce(Reduction(Vector(), "count", 0x2000), Const(CBoolean(false))(Line(0, "")))(Line(0, "")))
    }

    "parse out distinct" in {
      val result = decorate(Vector(Line(0, ""), PushNull, instructions.Distinct))
      result mustEqual Right(dag.Distinct(Const(CNull)(Line(0, "")))(Line(0, "")))
    }
    
    // TODO morphisms

    "parse an array join" in {
      val result = decorate(Vector(
        Line(0, ""),
        PushString("/summer_games/london_medals"), 
        instructions.LoadLocal, 
        Dup, 
        PushString("Weight"), 
        Map2Cross(DerefObject), 
        instructions.Reduce(BuiltInReduction(Reduction(Vector(), "max", 0x2001))),
        Map1(WrapArray), 
        Swap(1), 
        PushString("HeightIncm"), 
        Map2Cross(DerefObject), 
        instructions.Reduce(BuiltInReduction(Reduction(Vector(), "max", 0x2001))),
        Map1(WrapArray), 
        Map2Cross(JoinArray)))

      val line = Line(0, "")
      val medals = dag.LoadLocal(Const(CString("/summer_games/london_medals"))(line))(line)

      val expected = Join(JoinArray, CrossLeftSort,
        Operate(WrapArray,
          dag.Reduce(Reduction(Vector(), "max", 0x2001), 
            Join(DerefObject, CrossLeftSort,
              medals,
              Const(CString("Weight"))(line))(line))(line))(line),
        Operate(WrapArray,
          dag.Reduce(Reduction(Vector(), "max", 0x2001), 
            Join(DerefObject, CrossLeftSort,
              medals,
              Const(CString("HeightIncm"))(line))(line))(line))(line))(line)

      
      result mustEqual Right(expected)
    }
    
    "parse a single-level split" in {
      val line = Line(0, "")
      
      val result = decorate(Vector(
        line,
        PushTrue,
        Dup,
        KeyPart(1),
        Swap(1),
        instructions.Group(2),
        instructions.Split,
        PushGroup(2),
        PushKey(1),
        IUnion,
        Merge))
        
      result must beLike {
        case Right(
          s @ dag.Split(
            dag.Group(2,
              Const(CBoolean(true)),
              UnfixedSolution(1, Const(CBoolean(true)))),
            IUI(true, 
              sg: SplitGroup,
              sp: SplitParam))) => {
              
          sp.id mustEqual 1
          sg.id mustEqual 2
          sg.identities mustEqual Identities.Specs(Vector())
          
          sp.parent mustEqual s
          sg.parent mustEqual s
        }
      }
    }
    
    "parse a bi-level split" in {
      val line = Line(0, "")
      
      val result = decorate(Vector(
        line,
        PushTrue,
        KeyPart(1),
        PushFalse,
        instructions.Group(2),
        instructions.Split,
        PushGroup(2),
        KeyPart(3),
        PushKey(1),
        instructions.Group(4),
        instructions.Split,
        PushGroup(4),
        PushKey(3),
        IUnion,
        Merge,
        Merge))
        
      result must beLike {
        case Right(
          s1 @ dag.Split(
            dag.Group(2, Const(CBoolean(false)), UnfixedSolution(1, Const(CBoolean(true)))),
            s2 @ dag.Split(
              dag.Group(4, sp1: SplitParam, UnfixedSolution(3, sg1: SplitGroup)),
              IUI(true,
                sg2: SplitGroup,
                sp2: SplitParam)))) => {
          
          sp1.id mustEqual 1
          sg1.id mustEqual 2
          sg1.identities mustEqual Identities.Specs(Vector())
          
          sp1.parent mustEqual s1
          sg1.parent mustEqual s1
          
          sp2.id mustEqual 3
          sp2.identities mustEqual Identities.Specs(Vector())
          
          sp2.parent mustEqual s2
          sg2.parent mustEqual s2
        }
      }
    }
    
    "parse a bi-level split with intermediate usage" in {
      val line = Line(0, "")
      
      val result = decorate(Vector(
        line,
        PushTrue,
        KeyPart(1),
        PushFalse,
        instructions.Group(2),
        instructions.Split,
        PushGroup(2),
        PushKey(1),
        Map2Cross(Add),
        PushNum("42"),
        KeyPart(3),
        PushFalse,
        instructions.Group(4),
        instructions.Split,
        PushGroup(4),
        IUnion,
        Merge,
        Merge))
      
      result must beLike {
        case Right(
          s1 @ dag.Split(
            dag.Group(2, Const(CBoolean(false)), UnfixedSolution(1, Const(CBoolean(true)))),
            s2 @ dag.Split(
              dag.Group(4, Const(CBoolean(false)), UnfixedSolution(3, Const(CLong(42)))),
              IUI(true,
                Join(Add, CrossLeftSort,
                  sg1: SplitGroup,
                  sp1: SplitParam),
                sg2: SplitGroup)))) => {
          
          sp1.id mustEqual 1
          sg1.id mustEqual 2
          sg1.identities mustEqual Identities.Specs(Vector())
          
          sg2.id mustEqual 4
          sg2.identities mustEqual Identities.Specs(Vector())
          
          sp1.parent mustEqual s1
          sg1.parent mustEqual s1
          
          sg2.parent mustEqual s2
        }
      }
    }
    
    "parse a split with merged buckets" >> {
      "union" >> {
        val line = Line(0, "")
        
        val result = decorate(Vector(
          line,
          PushNum("1"),
          KeyPart(1),
          PushNum("3"),
          KeyPart(1),
          MergeBuckets(false),
          PushNum("2"),
          instructions.Group(3),
          instructions.Split,
          PushGroup(3),
          PushKey(1),
          IUnion,
          Merge))
          
        result must beLike {
          case Right(
            s @ dag.Split(
              dag.Group(3,
                Const(CLong(2)),
                UnionBucketSpec(
                  UnfixedSolution(1, Const(CLong(1))),
                  UnfixedSolution(1, Const(CLong(3))))),
              IUI(true,
                sg: SplitGroup,
                sp: SplitParam))) => {
                  
            sp.id mustEqual 1
            sg.id mustEqual 3
            sg.identities mustEqual Identities.Specs(Vector())
            
            sg.parent mustEqual s
            sp.parent mustEqual s
          }
        }
      }
      
      "intersect" >> {
        val line = Line(0, "")
        
        val result = decorate(Vector(
          line,
          PushNum("1"),
          KeyPart(1),
          PushNum("3"),
          KeyPart(1),
          MergeBuckets(true),
          PushNum("2"),
          instructions.Group(3),
          instructions.Split,
          PushGroup(3),
          PushKey(1),
          IUnion,
          Merge))
          
        result must beLike {
          case Right(
            s @ dag.Split(
              dag.Group(3,
                Const(CLong(2)),
                IntersectBucketSpec(
                  UnfixedSolution(1, Const(CLong(1))),
                  UnfixedSolution(1, Const(CLong(3))))),
              IUI(true,
                sg: SplitGroup,
                sp: SplitParam))) => {
            
            sp.id mustEqual 1
            sg.id mustEqual 3
            sg.identities mustEqual Identities.Specs(Vector())
            
            sg.parent mustEqual s
            sp.parent mustEqual s
          }
        }
      }
    }
    
    // TODO union zip and zip with multiple keys
    "parse a split with zipped buckets" in {
      val line = Line(0, "")
      
      val result = decorate(Vector(
        line,
        PushNum("1"),
        KeyPart(1),
        PushNum("2"),
        instructions.Group(2),
        PushNum("3"),
        KeyPart(1),
        PushNum("4"),
        instructions.Group(3),
        MergeBuckets(true),
        instructions.Split,
        PushGroup(2),
        PushGroup(3),
        PushKey(1),
        IUnion,
        IUnion,
        Merge))
        
      result must beLike {
        case Right(
          s @ dag.Split(
            IntersectBucketSpec(
              dag.Group(2, Const(CLong(2)), UnfixedSolution(1, Const(CLong(1)))),
              dag.Group(3, Const(CLong(4)), UnfixedSolution(1, Const(CLong(3))))),
            IUI(true,
              sg2: SplitGroup,
              IUI(true,
                sg1: SplitGroup,
                sp1: SplitParam)))) => {
          
          sp1.id mustEqual 1
          sg1.id mustEqual 3
          sg1.identities mustEqual Identities.Specs(Vector())
          
          sg2.id mustEqual 2
          sg2.identities mustEqual Identities.Specs(Vector())
                  
          sg1.parent mustEqual s
          sp1.parent mustEqual s
          sg2.parent mustEqual s
        }
      }
    }
    
    "accept split which reduces the stack" in {
      val line = Line(0, "")
      
      val result = decorate(Vector(
        line,
        PushNum("42"),
        PushTrue,
        KeyPart(1),
        PushNull,
        instructions.Group(2),
        instructions.Split,
        PushGroup(2),
        Map2Match(Add),
        Merge))
        
      result must beLike {
        case Right(
          s @ dag.Split(
            dag.Group(2, Const(CNull), UnfixedSolution(1, Const(CBoolean(true)))),
            Join(Add, IdentitySort,
              Const(CLong(42)),
              sg: SplitGroup))) => {
          
          sg.id mustEqual 2
          sg.identities mustEqual Identities.Specs(Vector())
          sg.parent mustEqual s
        }
      }
    }

    "determine a histogram of a composite key of revenue and campaign" in {
      val line = Line(0, "")

      val result = decorate(Vector(
        Line(0, ""),
        PushString("/organizations"),
        instructions.LoadLocal,
        Dup,
        Dup,
        Dup,
        PushString("revenue"),
        Map2Cross(DerefObject),
        KeyPart(1),
        Swap(1),
        PushString("revenue"),
        Map2Cross(DerefObject),
        instructions.Group(0),
        Swap(1),
        PushString("campaign"),
        Map2Cross(DerefObject),
        KeyPart(3),
        Swap(1),
        Swap(2),
        PushString("campaign"),
        Map2Cross(DerefObject),
        instructions.Group(2),
        MergeBuckets(true),
        PushString("/campaigns"),
        instructions.LoadLocal,
        Dup,
        Swap(2),
        Swap(1),
        PushString("campaign"),
        Map2Cross(DerefObject),
        KeyPart(3),
        Swap(1),
        Swap(2),
        instructions.Group(4),
        MergeBuckets(true),
        instructions.Split,
        PushString("revenue"),
        PushKey(1),
        Map2Cross(WrapObject),
        PushString("num"),
        PushGroup(4),
        instructions.Reduce(BuiltInReduction(Reduction(Vector(), "count", 0x002000))),
        Map2Cross(WrapObject),
        Map2Cross(JoinObject),
        Merge))
  
        val JUniverseT = JUnionT(JUnionT(JUnionT(JUnionT(JUnionT(JNumberT, JTextT), JBooleanT),JNullT), JObjectUnfixedT), JArrayUnfixedT)

        val expectedSpec = IntersectBucketSpec(
          IntersectBucketSpec(
              dag.Group(0,
                  Join(DerefObject,CrossLeftSort,
                      dag.LoadLocal(Const(CString("/organizations"))(line), JUniverseT)(line),
                      Const(CString("revenue"))(line))(line),
                  UnfixedSolution(1,
                      Join(DerefObject,CrossLeftSort,
                          dag.LoadLocal(Const(CString("/organizations"))(line), JUniverseT)(line),
                          Const(CString("revenue"))(line))(line))),
              dag.Group(2,
                  Join(DerefObject,CrossLeftSort,
                      dag.LoadLocal(Const(CString("/organizations"))(line), JUniverseT)(line),
                      Const(CString("campaign"))(line))(line),
                  UnfixedSolution(3,
                      Join(DerefObject,CrossLeftSort,
                      dag.LoadLocal(Const(CString("/organizations"))(line), JUniverseT)(line),
                      Const(CString("campaign"))(line))(line)))),
          dag.Group(4,
              dag.LoadLocal(Const(CString("/campaigns"))(line), JUniverseT)(line),
              UnfixedSolution(3,
                  Join(DerefObject,CrossLeftSort,
                      dag.LoadLocal(Const(CString("/campaigns"))(line), JUniverseT)(line),
                      Const(CString("campaign"))(line))(line))))
    
    lazy val expectedSplit: dag.Split = dag.Split(expectedSpec, expectedTarget)(line)
      
    lazy val expectedTarget = Join(JoinObject,CrossLeftSort,
      Join(WrapObject,CrossLeftSort,
        Const(CString("revenue"))(line),
        SplitParam(1)(expectedSplit)(line))(line),
      Join(WrapObject,CrossLeftSort,
        Const(CString("num"))(line),
        dag.Reduce(Reduction(Vector(), "count", 0x002000),SplitGroup(4,Identities.Specs(Vector(LoadIds("/campaigns"))))(expectedSplit)(line))(line))(line))(line)


      result mustEqual Right(expectedSplit)
    }
    
    "recognize a join instruction" in {
      "map2_match" >> {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushTrue, PushFalse, Map2Match(Add)))
        result mustEqual Right(Join(Add, IdentitySort, Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
      }
      
      "map2_cross" >> {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushTrue, PushFalse, Map2Cross(Add)))
        result mustEqual Right(Join(Add, CrossLeftSort, Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
      }
      
      "assert" >> {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushTrue, PushNum("42"), instructions.Assert))
        result mustEqual Right(dag.Assert(Const(CBoolean(true))(line), Const(CLong(42))(line))(line))
      }
      
      "iunion" >> {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushTrue, PushFalse, IUnion))
        result mustEqual Right(IUI(true, Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
      }
      
      "iintersect" >> {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushTrue, PushFalse, IIntersect))
        result mustEqual Right(IUI(false, Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
      }      

      "set difference" >> {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushTrue, PushFalse, SetDifference))
        result mustEqual Right(Diff(Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
      }
    }
    
    "parse a filter with null predicate" in {
      val line = Line(0, "")
      val result = decorate(Vector(line, PushFalse, PushTrue, FilterMatch))
      result mustEqual Right(Filter(IdentitySort, Const(CBoolean(false))(line), Const(CBoolean(true))(line))(line))
    }
    
    "parse a filter_cross" in {
      val line = Line(0, "")
      val result = decorate(Vector(line, PushTrue, PushFalse, FilterCross))
      result mustEqual Right(Filter(CrossLeftSort, Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
    }
    
    "parse a filter_crossl" in {
      val line = Line(0, "")
      val result = decorate(Vector(line, PushTrue, PushFalse, FilterCrossLeft))
      result mustEqual Right(Filter(CrossLeftSort, Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
    }
    
    "parse a filter_crossr" in {
      val line = Line(0, "")
      val result = decorate(Vector(line, PushTrue, PushFalse, FilterCrossRight))
      result mustEqual Right(Filter(CrossRightSort, Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
    }
    
    "continue processing beyond a filter" in {
      val line = Line(0, "")
      val result = decorate(Vector(line, PushFalse, PushTrue, FilterMatch, Map1(Neg)))
      result mustEqual Right(
        Operate(Neg,
          Filter(IdentitySort,
            Const(CBoolean(false))(line),
            Const(CBoolean(true))(line))(line))(line))
    }
    
    "parse and factor a dup" in {
      {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushTrue, Dup, IUnion))
        result mustEqual Right(IUI(true, Const(CBoolean(true))(line), Const(CBoolean(true))(line))(line))
      }
      
      {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushNum("42"), Map1(Neg), Dup, IUnion))
        result mustEqual Right(IUI(true, Operate(Neg, Const(CLong(42))(line))(line), Operate(Neg, Const(CLong(42))(line))(line))(line))
      }
    }
    
    "parse and factor a swap" in {
      "1" >> {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushFalse, PushTrue, Swap(1), IUnion))
        result mustEqual Right(IUI(true, Const(CBoolean(true))(line), Const(CBoolean(false))(line))(line))
      }
      
      "3" >> {
        val line = Line(0, "")
        val result = decorate(Vector(line, PushTrue, PushString("foo"), PushFalse, PushNum("42"), Swap(3), IUnion, IUnion, IUnion))
        result mustEqual Right(
          IUI(true,
            Const(CLong(42))(line),
            IUI(true,
              Const(CString("foo"))(line),
              IUI(true, Const(CBoolean(false))(line), Const(CBoolean(true))(line))(line))(line))(line))
      }
    }
    
    // TODO line info
  }
  
  "stream validation" should {
    "reject the empty stream" in {
      decorate(Vector()) mustEqual Left(EmptyStream)
    }
    
    "reject a line-less stream" in {
      decorate(Vector(PushTrue)) mustEqual Left(UnknownLine)
      decorate(Vector(PushTrue, PushFalse, Map1(Comp))) mustEqual Left(UnknownLine)
    }
    
    "detect stack underflow" in {
      "map1" >> {     // historic sidebar: since we don't have pop, this is the *only* map1 underflow case!
        val instr = Map1(Neg)
        decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
      }
      
      "map2_match" >> {
        {
          val instr = Map2Match(Add)
          decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = Map2Match(Add)
          decorate(Vector(Line(0, ""), PushTrue, instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = Map2Match(Add)
          decorate(Vector(Line(0, ""), PushTrue, Map1(Comp), instr, Map2Match(Sub))) mustEqual Left(StackUnderflow(instr))
        }
      }
      
      "map2_cross" >> {
        {
          val instr = Map2Cross(Add)
          decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = Map2Cross(Add)
          decorate(Vector(Line(0, ""), PushTrue, instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = Map2Cross(Add)
          decorate(Vector(Line(0, ""), PushTrue, Map1(Comp), instr, Map2Cross(Sub))) mustEqual Left(StackUnderflow(instr))
        }
      }
      
      "reduce" >> {     // similar to map1, only one underflow case!
        val instr = instructions.Reduce(BuiltInReduction(Reduction(Vector(), "count", 0x2000)))
        decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
      }  

      "set-reduce" >> {     // similar to map1, only one underflow case!
        val instr = instructions.Distinct
        decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
      }
      
      "iunion" >> {     // similar to map1, only one underflow case!
        val instr = IUnion
        decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
      }
      
      "iintersect" >> {     // similar to map1, only one underflow case!
        val instr = IIntersect
        decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
      }      

      "set difference" >> {     // similar to map1, only one underflow case!
        val instr = SetDifference
        decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
      }
      
      "split" >> {     // similar to map1, only one underflow case!
        val instr = instructions.Split
        decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
      }
      
      // merge cannot stack underflow; curious, no?
      
      "filter_match" >> {
        {
          val instr = FilterMatch
          decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = FilterMatch
          decorate(Vector(Line(0, ""), PushTrue, instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = FilterMatch
          decorate(Vector(Line(0, ""), PushTrue, PushTrue, Map2Match(Add), instr)) mustEqual Left(StackUnderflow(instr))
        }
      }
      
      "filter_cross" >> {
        {
          val instr = FilterCross
          decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = FilterCross
          decorate(Vector(Line(0, ""), PushTrue, instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = FilterCross
          decorate(Vector(Line(0, ""), PushTrue, PushTrue, Map2Match(Add), instr)) mustEqual Left(StackUnderflow(instr))
        }
      }
      
      "dup" >> {
        decorate(Vector(Line(0, ""), Dup)) mustEqual Left(StackUnderflow(Dup))
      }
      
      "swap" >> {
        {
          val instr = Swap(1)
          decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = Swap(1)
          decorate(Vector(Line(0, ""), PushTrue, instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = Swap(2)
          decorate(Vector(Line(0, ""), PushTrue, PushTrue, instr)) mustEqual Left(StackUnderflow(instr))
        }
        
        {
          val instr = Swap(5)
          decorate(Vector(Line(0, ""), PushTrue, PushTrue, instr)) mustEqual Left(StackUnderflow(instr))
        }
      }
      
      "load_local" >> {
        val instr = instructions.LoadLocal
        decorate(Vector(Line(0, ""), instr)) mustEqual Left(StackUnderflow(instr))
      }
    }
    
    "reject multiple stack values at end" in {
      decorate(Vector(Line(0, ""), PushTrue, PushFalse)) mustEqual Left(MultipleStackValuesAtEnd)
      decorate(Vector(Line(0, ""), PushTrue, PushFalse, PushNum("42"))) mustEqual Left(MultipleStackValuesAtEnd)
      decorate(Vector(Line(0, ""), PushTrue, PushFalse, PushNum("42"), PushString("foo"))) mustEqual Left(MultipleStackValuesAtEnd)
    }
    
    "reject negative swap depth" in {
      {
        val instr = Swap(-1)
        decorate(Vector(Line(0, ""), PushTrue, instr)) mustEqual Left(NonPositiveSwapDepth(instr))
      }
      
      {
        val instr = Swap(-255)
        decorate(Vector(Line(0, ""), PushTrue, instr)) mustEqual Left(NonPositiveSwapDepth(instr))
      }
    }
    
    "reject zero swap depth" in {
      val instr = Swap(0)
      decorate(Vector(Line(0, ""), PushTrue, instr)) mustEqual Left(NonPositiveSwapDepth(instr))
    }
    
    "reject merge with deepened stack" in {
      decorate(Vector(
        Line(0, ""),
        PushTrue,
        KeyPart(1),
        PushFalse,
        instructions.Group(2),
        instructions.Split,
        PushKey(1),
        PushGroup(2),
        PushFalse,
        Merge,
        Drop,
        Drop)) mustEqual Left(MergeWithUnmatchedTails)
    }
    
    "accept merge with reduced (but reordered) stack" in {
      val line = Line(0, "")
      
      val result = decorate(Vector(
        line,
        PushTrue,
        PushFalse,
        PushNum("42"),
        KeyPart(1),
        PushNum("12"),
        instructions.Group(2),
        instructions.Split,
        PushGroup(2),
        Swap(1),
        Swap(2),
        IUnion,
        Merge,
        IIntersect))
      
      lazy val split: dag.Split = dag.Split(
        dag.Group(2, Const(CLong(12))(line), UnfixedSolution(1, Const(CLong(42))(line))),
        IUI(true,
          SplitGroup(2, Identities.Specs(Vector()))(split)(line),
          Const(CBoolean(true))(line))(line))(line)
      
      val expect = IUI(false, Const(CBoolean(false))(line), split)(line)
        
      result mustEqual Right(expect)
    }
    
    "reject unmatched merge" in {
      decorate(Vector(Line(0, ""), PushTrue, Merge)) mustEqual Left(UnmatchedMerge)
    }
    
    "reject split without corresponding merge" in {
      decorate(Vector(Line(0, ""),
        PushTrue,
        KeyPart(1),
        PushFalse,
        instructions.Group(2),
        instructions.Split)) mustEqual Left(UnmatchedSplit)
    }
    
    "reject split which increases the stack" in {
      val line = Line(0, "")
      
      val result = decorate(Vector(
        line,
        PushTrue,
        KeyPart(1),
        PushFalse,
        instructions.Group(2),
        instructions.Split,
        PushGroup(2),
        PushTrue,
        Merge))
        
      result mustEqual Left(MergeWithUnmatchedTails)
    }
  }
  
  "mapDown" should {
    "rewrite a LoadLocal shared across Split branches to the same object" in {
      val line = Line(0, "")
      val load = dag.LoadLocal(Const(CString("/clicks"))(line))(line)
      
      lazy val input: dag.Split = dag.Split(
        dag.Group(1, load, UnfixedSolution(0, load)),
        SplitParam(0)(input)(line))(line)
        
      val result = input.mapDown { recurse => {
        case graph @ dag.LoadLocal(Const(CString(path)), tpe) =>
          dag.LoadLocal(Const(CString("/foo" + path))(graph.loc), tpe)(graph.loc)
      }}
      
      result must beLike {
        case dag.Split(dag.Group(_, load1, UnfixedSolution(_, load2)), _) =>
          load1 must be(load2)
      }
    }
  }
  
  "foldDown" should {
    "look within a Split branch" in {
      val line = Line(0, "")
      val load = dag.LoadLocal(Const(CString("/clicks"))(line))(line)
      
      lazy val input: dag.Split = dag.Split(
        dag.Group(1, load, UnfixedSolution(0, load)),
        SplitParam(0)(input)(line))(line)
        
      import scalaz.std.anyVal._
      val result = input.foldDown[Int](true) {
        case _: LoadLocal => 1
      }
      
      result mustEqual 2
    }
  }
}
