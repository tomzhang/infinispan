package org.infinispan.objectfilter.impl.aggregation;

/**
 * An accumulator that returns the smallest of the values it encounters. Values must be {@link Comparable}. The return
 * has the same type as the field to which it is applied. {@code Null} values are ignored. If there are no remaining
 * non-null values to compute then the result of the aggregate function is {@code null}.
 *
 * @author anistor@redhat.com
 * @since 8.0
 */
public final class MinAccumulator extends FieldAccumulator {

   public MinAccumulator(int pos, Class<?> fieldType) {
      super(pos);
      if (!Comparable.class.isAssignableFrom(fieldType)) {
         throw new IllegalStateException("Aggregation MIN cannot be applied to property of type " + fieldType.getName());
      }
   }

   @Override
   public void update(Object[] srcRow, Object[] accRow) {
      Comparable value = (Comparable) srcRow[pos];
      if (value != null) {
         Comparable min = (Comparable) accRow[pos];
         if (min == null || min.compareTo(value) > 0) {
            accRow[pos] = value;
         }
      }
   }
}
