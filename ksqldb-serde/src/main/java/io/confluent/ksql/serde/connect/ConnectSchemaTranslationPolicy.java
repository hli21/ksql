/*
 * Copyright 2021 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.serde.connect;

import com.google.common.collect.ImmutableSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.confluent.ksql.util.CompatibleElement;
import java.util.Arrays;
import java.util.Set;

/**
 * Connect schema translation policy for translator
 */
public enum ConnectSchemaTranslationPolicy implements
    CompatibleElement<ConnectSchemaTranslationPolicy> {

  /**
   * This policy will uppercase field name during schema translation
   *
   * @see ConnectSchemaTranslationPolicy#ORIGINAL_FIELD_NAME
   */
  UPPERCASE_FIELD_NAME("ORIGINAL_FIELD_NAME"),

  /**
   * This policy will keep original field name during schema translation
   *
   * @see ConnectSchemaTranslationPolicy#UPPERCASE_FIELD_NAME
   */
  ORIGINAL_FIELD_NAME("UPPERCASE_FIELD_NAME");

  private final ImmutableSet<String> unvalidated;
  private ImmutableSet<ConnectSchemaTranslationPolicy> incompatibleWith;

  ConnectSchemaTranslationPolicy(final String... incompatibleWith) {
    this.unvalidated = ImmutableSet.copyOf(incompatibleWith);
  }

  @SuppressFBWarnings(value = "EI_EXPOSE_REP", justification = "incompatibleWith is ImmutableSet")
  @Override
  public Set<ConnectSchemaTranslationPolicy> getIncompatibleWith() {
    return incompatibleWith;
  }

  private void validate() {
    this.incompatibleWith = unvalidated.stream()
        .map(ConnectSchemaTranslationPolicy::valueOf)
        .collect(ImmutableSet.toImmutableSet());
  }

  static {
    Arrays.stream(ConnectSchemaTranslationPolicy.values())
        .forEach(ConnectSchemaTranslationPolicy::validate);
  }
}
