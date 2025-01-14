/*
 * Copyright 2018 Confluent Inc.
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

package io.confluent.ksql.parser.tree;

import static com.google.common.base.MoreObjects.toStringHelper;

import com.google.errorprone.annotations.Immutable;
import io.confluent.ksql.name.SourceName;
import io.confluent.ksql.parser.NodeLocation;
import io.confluent.ksql.parser.exception.ParseFailedException;
import io.confluent.ksql.parser.properties.with.CreateSourceProperties;
import io.confluent.ksql.parser.tree.TableElement.Namespace;
import java.util.Optional;

@Immutable
public class CreateTable extends CreateSource implements ExecutableDdlStatement {
  public CreateTable(
      final SourceName name,
      final TableElements elements,
      final boolean orReplace,
      final boolean notExists,
      final CreateSourceProperties properties,
      final boolean isSource
  ) {
    this(Optional.empty(), name, elements, orReplace, notExists, properties, isSource);
  }

  public CreateTable(
      final Optional<NodeLocation> location,
      final SourceName name,
      final TableElements elements,
      final boolean orReplace,
      final boolean notExists,
      final CreateSourceProperties properties,
      final boolean isSource
  ) {
    super(location, name, elements, orReplace, notExists, properties, isSource);

    throwOnNonPrimaryKeys(elements);
  }

  @Override
  public CreateSource copyWith(
      final TableElements elements,
      final CreateSourceProperties properties
  ) {
    return new CreateTable(
        getLocation(),
        getName(),
        elements,
        isOrReplace(),
        isNotExists(),
        properties,
        isSource());
  }

  @Override
  public <R, C> R accept(final AstVisitor<R, C> visitor, final C context) {
    return visitor.visitCreateTable(this, context);
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if ((obj == null) || (getClass() != obj.getClass())) {
      return false;
    }
    return super.equals(obj);
  }

  @Override
  public String toString() {
    return toStringHelper(this)
        .add("name", getName())
        .add("elements", getElements())
        .add("orReplace", isOrReplace())
        .add("notExists", isNotExists())
        .add("properties", getProperties())
        .add("isSource", isSource())
        .toString();
  }

  private static void throwOnNonPrimaryKeys(final TableElements elements) {
    final Optional<TableElement> wrongKey = elements.stream()
        .filter(e -> e.getNamespace().isKey() && e.getNamespace() != Namespace.PRIMARY_KEY)
        .findFirst();

    wrongKey.ifPresent(col -> {
      final String loc = NodeLocation.asPrefix(col.getLocation());
      throw new ParseFailedException(
          loc + "Column " + col.getName() + " is a 'KEY' column: "
              + "please use 'PRIMARY KEY' for tables."
              + System.lineSeparator()
              + "Tables have PRIMARY KEYs, which are unique and NON NULL."
              + System.lineSeparator()
              + "Streams have KEYs, which have no uniqueness or NON NULL constraints."
      );
    });
  }
}
