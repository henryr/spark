/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule

/**
* Simplify redundant [[CreateNamedStructLike]], [[CreateArray]] and [[CreateMap]] expressions.
*/
object SimplifyCreateStructOps extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan transform { case p =>
    p.transformExpressionsUp {
      // Remove redundant field extraction.
      case GetStructField(createNamedStructLike: CreateNamedStructLike, ordinal, _) =>
        createNamedStructLike.valExprs(ordinal)

      // Remove redundant array indexing.
      case GetArrayStructFields(CreateArray(elems), field, ordinal, numFields, containsNull) =>
        // instead f selecting the field on the entire array,
        // select it from each member of the array.
        // pushing down the operation this way open other optimizations opportunities
        // (i.e. struct(...,x,...).x)
        CreateArray(elems.map(GetStructField(_, ordinal, Some(field.name))))

      // Remove redundant map lookup.
      case ga @ GetArrayItem(CreateArray(elems), IntegerLiteral(idx)) =>
        // instead of creating the array and then selecting one row,
        // remove array creation altgether.
        if (idx >= 0 && idx < elems.size) {
          // valid index
          elems(idx)
        } else {
          // out of bounds, mimic the runtime behavior and return null
          Literal(null, ga.dataType)
        }
      case GetMapValue(CreateMap(elems), key) => CaseKeyWhen(key, elems)
    }
  }
}
