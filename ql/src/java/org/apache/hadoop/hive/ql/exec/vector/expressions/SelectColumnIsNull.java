/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector.expressions;

import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorExpressionDescriptor;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;

/**
 * This expression selects a row if the given column is null.
 */
public class SelectColumnIsNull extends VectorExpression {
  private static final long serialVersionUID = 1L;
  private int colNum;

  public SelectColumnIsNull(int colNum) {
    this();
    this.colNum = colNum;
  }

  public SelectColumnIsNull() {
    super();
  }


  @Override
  public void evaluate(VectorizedRowBatch batch) {
    if (childExpressions != null) {
      super.evaluateChildren(batch);
    }
    ColumnVector inputColVector = batch.cols[colNum];
    int[] sel = batch.selected;
    boolean[] nullPos = inputColVector.isNull;
    int n = batch.size;
    if (n <= 0) {
      // Nothing to do
      return;
    }

    if (inputColVector.noNulls) {
      batch.size = 0;
    } else if (inputColVector.isRepeating) {
      if (nullPos[0]) {
        // All are null, so all must be selected.
        return;
      } else {
        // None are null, so none are selected
        batch.size = 0;
        return;
      }
    } else if (batch.selectedInUse) {
      int newSize = 0;
      for (int j = 0; j != n; j++) {
        int i = sel[j];
        if (nullPos[i]) {
          sel[newSize++] = i;
        }
      }
      batch.size = newSize;
    } else {
      int newSize = 0;
      for (int i = 0; i != n; i++) {
        if (nullPos[i]) {
          sel[newSize++] = i;
        }
      }
      if (newSize < n) {
        batch.selectedInUse = true;
        batch.size = newSize;
      }
    }
  }

  @Override
  public int getOutputColumn() {
    return -1;
  }

  @Override
  public String getOutputType() {
    return "boolean";
  }

  public int getColNum() {
    return colNum;
  }

  public void setColNum(int colNum) {
    this.colNum = colNum;
  }

  @Override
  public VectorExpressionDescriptor.Descriptor getDescriptor() {
    VectorExpressionDescriptor.Builder b = new VectorExpressionDescriptor.Builder();
    b.setMode(VectorExpressionDescriptor.Mode.FILTER)
        .setNumArguments(1)
        .setArgumentTypes(
            VectorExpressionDescriptor.ArgumentType.ALL_FAMILY)
        .setInputExpressionTypes(
            VectorExpressionDescriptor.InputExpressionType.COLUMN);
    return b.build();
  }
}
