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

package org.apache.hadoop.hive.ql.udf.generic;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hive.common.type.HiveDecimal;
import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.NoMatchingMethodException;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.exec.UDFArgumentTypeException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.plan.ExprNodeDesc;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.serde2.io.ByteWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.io.HiveDecimalWritable;
import org.apache.hadoop.hive.serde2.io.ShortWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorConverters;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorConverters.Converter;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils.PrimitiveGrouping;
import org.apache.hadoop.hive.serde2.typeinfo.DecimalTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.HiveDecimalUtils;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoUtils;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hive.common.HiveCompat;
import org.apache.hive.common.HiveCompat.CompatLevel;

/**
 * GenericUDF Base Class for operations.
 */
@Description(name = "op", value = "a op b - Returns the result of operation")
public abstract class GenericUDFBaseNumeric extends GenericUDF {
  protected String opName;
  protected String opDisplayName;

  protected transient PrimitiveObjectInspector leftOI;
  protected transient PrimitiveObjectInspector rightOI;
  protected transient PrimitiveObjectInspector resultOI;

  protected transient Converter converterLeft;
  protected transient Converter  converterRight;

  protected ByteWritable byteWritable = new ByteWritable();
  protected ShortWritable shortWritable = new ShortWritable();
  protected IntWritable intWritable = new IntWritable();
  protected LongWritable longWritable = new LongWritable();
  protected FloatWritable floatWritable = new FloatWritable();
  protected DoubleWritable doubleWritable = new DoubleWritable();
  protected HiveDecimalWritable decimalWritable = new HiveDecimalWritable();

  protected boolean confLookupNeeded = true;
  protected boolean ansiSqlArithmetic = false;

  public GenericUDFBaseNumeric() {
    opName = getClass().getSimpleName();
  }

  @Override
  public ObjectInspector initialize(ObjectInspector[] arguments) throws UDFArgumentException {
    if (arguments.length != 2) {
      throw new UDFArgumentException(opName + " requires two arguments.");
    }

    for (int i = 0; i < 2; i++) {
      Category category = arguments[i].getCategory();
      if (category != Category.PRIMITIVE) {
        throw new UDFArgumentTypeException(i, "The "
            + GenericUDFUtils.getOrdinal(i + 1)
            + " argument of " + opName + "  is expected to a "
            + Category.PRIMITIVE.toString().toLowerCase() + " type, but "
            + category.toString().toLowerCase() + " is found");
      }
    }

    // During map/reduce tasks, there may not be a valid HiveConf from the SessionState.
    // So lookup and save any needed conf information during query compilation in the Hive conf
    // (where there should be valid HiveConf from SessionState).  Plan serialization will ensure
    // we have access to these values in the map/reduce tasks.
    if (confLookupNeeded) {
      CompatLevel compatLevel = HiveCompat.getCompatLevel(SessionState.get().getConf());
      ansiSqlArithmetic = compatLevel.ordinal() > CompatLevel.HIVE_0_12.ordinal();
      confLookupNeeded = false;
    }

    leftOI = (PrimitiveObjectInspector) arguments[0];
    rightOI = (PrimitiveObjectInspector) arguments[1];
    resultOI = PrimitiveObjectInspectorFactory.getPrimitiveWritableObjectInspector(
        deriveResultTypeInfo());
    converterLeft = ObjectInspectorConverters.getConverter(leftOI, resultOI);
    converterRight = ObjectInspectorConverters.getConverter(rightOI, resultOI);

    return resultOI;
  }

  @Override
  public Object evaluate(DeferredObject[] arguments) throws HiveException {
    if (arguments[0] == null || arguments[1] == null) {
      return null;
    }

    Object left = arguments[0].get();
    Object right = arguments[1].get();
    if (left == null && right == null) {
      return null;
    }

    // Handle decimal separately.
    if (resultOI.getPrimitiveCategory() == PrimitiveCategory.DECIMAL) {
      HiveDecimal hdLeft = PrimitiveObjectInspectorUtils.getHiveDecimal(left, leftOI);
      HiveDecimal hdRight = PrimitiveObjectInspectorUtils.getHiveDecimal(right, rightOI);
      if (hdLeft == null || hdRight == null) {
        return null;
      }
      HiveDecimalWritable result = evaluate(hdLeft, hdRight);
      return resultOI.getPrimitiveWritableObject(result);
    }

    left = converterLeft.convert(left);
    if (left == null) {
      return null;
    }
    right = converterRight.convert(right);
    if (right == null) {
      return null;
    }

    switch (resultOI.getPrimitiveCategory()) {
    case BYTE:
      return evaluate((ByteWritable) left, (ByteWritable) right);
    case SHORT:
      return evaluate((ShortWritable) left, (ShortWritable) right);
    case INT:
      return evaluate((IntWritable) left, (IntWritable) right);
    case LONG:
      return evaluate((LongWritable) left, (LongWritable) right);
    case FLOAT:
      return evaluate((FloatWritable) left, (FloatWritable) right);
    case DOUBLE:
      return evaluate((DoubleWritable) left, (DoubleWritable) right);
    default:
      // Should never happen.
      throw new RuntimeException("Unexpected type in evaluating " + opName + ": " +
        resultOI.getPrimitiveCategory());
    }
  }

  protected ByteWritable evaluate(ByteWritable left, ByteWritable right) {
    return null;
  }

  protected ShortWritable evaluate(ShortWritable left, ShortWritable right) {
    return null;
  }

  protected IntWritable evaluate(IntWritable left, IntWritable right) {
    return null;
  }

  protected LongWritable evaluate(LongWritable left, LongWritable right) {
    return null;
  }

  protected FloatWritable evaluate(FloatWritable left, FloatWritable right) {
    return null;
  }

  protected DoubleWritable evaluate(DoubleWritable left, DoubleWritable right) {
    return null;
  }

  protected HiveDecimalWritable evaluate(HiveDecimal left, HiveDecimal right) {
    return null;
  }

  /**
   * Default implementation for deriving typeinfo instance for the operator result.
   *
   * @param leftOI TypeInfo instance of the left operand
   * @param rightOI TypeInfo instance of the right operand
   * @return
   * @throws UDFArgumentException
   */
  private PrimitiveTypeInfo deriveResultTypeInfo() throws UDFArgumentException {
    PrimitiveTypeInfo left = (PrimitiveTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(leftOI);
    PrimitiveTypeInfo right = (PrimitiveTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(rightOI);
    if (!FunctionRegistry.isNumericType(left) || !FunctionRegistry.isNumericType(right)) {
      List<TypeInfo> argTypeInfos = new ArrayList<TypeInfo>(2);
      argTypeInfos.add(left);
      argTypeInfos.add(right);
      throw new NoMatchingMethodException(this.getClass(), argTypeInfos, null);
    }

    // If any of the type isn't exact, double is chosen.
    if (!FunctionRegistry.isExactNumericType(left) || !FunctionRegistry.isExactNumericType(right)) {
      return deriveResultApproxTypeInfo();
    }

    return deriveResultExactTypeInfo();
  }

  /**
   * Default implementation for getting the approximate type info for the operator result.
   * Divide operator overrides this.
   * @return
   */
  protected PrimitiveTypeInfo deriveResultApproxTypeInfo() {
    PrimitiveTypeInfo left = (PrimitiveTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(leftOI);
    PrimitiveTypeInfo right = (PrimitiveTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(rightOI);

    // string types get converted to double
    if (PrimitiveObjectInspectorUtils.getPrimitiveGrouping(left.getPrimitiveCategory())
            == PrimitiveGrouping.STRING_GROUP) {
      left = TypeInfoFactory.doubleTypeInfo;
    }
    if (PrimitiveObjectInspectorUtils.getPrimitiveGrouping(right.getPrimitiveCategory())
        == PrimitiveGrouping.STRING_GROUP) {
      right = TypeInfoFactory.doubleTypeInfo;
    }    

    // Use type promotion
    PrimitiveCategory commonCat = FunctionRegistry.getCommonCategory(left, right);
    if (commonCat == PrimitiveCategory.DECIMAL) {
      // Hive 0.12 behavior where double * decimal -> decimal is gone.
      return TypeInfoFactory.doubleTypeInfo;
    } else if (commonCat == null) {
      return TypeInfoFactory.doubleTypeInfo;
    } else {
      return left.getPrimitiveCategory() == commonCat ? left : right;
    }
  }

  /**
   * Default implementation for getting the exact type info for the operator result. It worked for all
   * but divide operator.
   *
   * @return
   */
  protected PrimitiveTypeInfo deriveResultExactTypeInfo() {
    PrimitiveTypeInfo left = (PrimitiveTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(leftOI);
    PrimitiveTypeInfo right = (PrimitiveTypeInfo) TypeInfoUtils.getTypeInfoFromObjectInspector(rightOI);

    // Now we are handling exact types. Base implementation handles type promotion.
    PrimitiveCategory commonCat = FunctionRegistry.getCommonCategory(left, right);
    if (commonCat == PrimitiveCategory.DECIMAL) {
      return deriveResultDecimalTypeInfo();
    } else {
      return left.getPrimitiveCategory() == commonCat ? left : right;
    }
  }

  /**
   * Derive the object inspector instance for the decimal result of the operator.
   */
  protected DecimalTypeInfo deriveResultDecimalTypeInfo() {
    int prec1 = leftOI.precision();
    int prec2 = rightOI.precision();
    int scale1 = leftOI.scale();
    int scale2 = rightOI.scale();
    return deriveResultDecimalTypeInfo(prec1, scale1, prec2, scale2);
  }

  protected abstract DecimalTypeInfo deriveResultDecimalTypeInfo(int prec1, int scale1, int prec2, int scale2);

  @Override
  public String getDisplayString(String[] children) {
    assert (children.length == 2) : opDisplayName + " with " + children.length + " children";
    return "(" + children[0] + " " + opDisplayName + " " + children[1] + ")";
  }

  public void copyToNewInstance(Object newInstance) throws UDFArgumentException {
    super.copyToNewInstance(newInstance);
    GenericUDFBaseNumeric other = (GenericUDFBaseNumeric) newInstance;
    other.confLookupNeeded = this.confLookupNeeded;
    other.ansiSqlArithmetic = this.ansiSqlArithmetic;
  }

  public boolean isConfLookupNeeded() {
    return confLookupNeeded;
  }

  public void setConfLookupNeeded(boolean confLookupNeeded) {
    this.confLookupNeeded = confLookupNeeded;
  }

  public boolean isAnsiSqlArithmetic() {
    return ansiSqlArithmetic;
  }

  public void setAnsiSqlArithmetic(boolean ansiSqlArithmetic) {
    this.ansiSqlArithmetic = ansiSqlArithmetic;
  }

  public PrimitiveTypeInfo deriveMinArgumentCast(
      ExprNodeDesc childExpr, TypeInfo targetType) {
    assert targetType instanceof PrimitiveTypeInfo : "Not a primitive type" + targetType;
    PrimitiveTypeInfo pti = (PrimitiveTypeInfo)targetType;
    // We only do the minimum cast for decimals. Other types are assumed safe; fix if needed.
    // We also don't do anything for non-primitive children (maybe we should assert).
    if ((pti.getPrimitiveCategory() != PrimitiveCategory.DECIMAL)
        || (!(childExpr.getTypeInfo() instanceof PrimitiveTypeInfo))) return pti;
    PrimitiveTypeInfo childTi = (PrimitiveTypeInfo)childExpr.getTypeInfo();
    // If the child is also decimal, no cast is needed (we hope - can target type be narrower?).
    return HiveDecimalUtils.getDecimalTypeForPrimitiveCategory(childTi);
  }
}
