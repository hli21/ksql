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

package io.confluent.ksql.function;

import static io.confluent.ksql.function.FunctionLoaderUtils.createFunctionInvoker;
import static io.confluent.ksql.function.UdfClassLoader.newClassLoader;
import static io.confluent.ksql.metastore.TypeRegistry.EMPTY;
import static io.confluent.ksql.name.FunctionName.of;
import static io.confluent.ksql.schema.ksql.SqlTypeParser.create;
import static io.confluent.ksql.schema.ksql.types.SqlTypes.decimal;
import static io.confluent.ksql.util.KsqlConfig.KSQL_FUNCTIONS_PROPERTY_PREFIX;
import static java.util.Collections.singletonList;
import static java.util.Optional.empty;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.confluent.ksql.execution.function.TableAggregationFunction;
import io.confluent.ksql.function.udaf.TestUdaf;
import io.confluent.ksql.function.udaf.Udaf;
import io.confluent.ksql.function.udf.Kudf;
import io.confluent.ksql.function.udf.PluggableUdf;
import io.confluent.ksql.function.udf.Udf;
import io.confluent.ksql.function.udf.UdfDescription;
import io.confluent.ksql.function.udf.UdfParameter;
import io.confluent.ksql.function.udf.UdfSchemaProvider;
import io.confluent.ksql.metastore.TypeRegistry;
import io.confluent.ksql.name.FunctionName;
import io.confluent.ksql.schema.ksql.SqlArgument;
import io.confluent.ksql.schema.ksql.SqlTypeParser;
import io.confluent.ksql.schema.ksql.types.SqlArray;
import io.confluent.ksql.schema.ksql.types.SqlDecimal;
import io.confluent.ksql.schema.ksql.types.SqlLambdaResolved;
import io.confluent.ksql.schema.ksql.types.SqlMap;
import io.confluent.ksql.schema.ksql.types.SqlStruct;
import io.confluent.ksql.schema.ksql.types.SqlType;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import io.confluent.ksql.test.util.KsqlTestFolder;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.KsqlException;
import java.io.File;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.metrics.KafkaMetric;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * This uses ksql-engine/src/test/resource/udf-example.jar to load the custom jars.
 * You can find the classes it is loading in the same directory
 */
public class UdfLoaderTest {

  private static final ClassLoader PARENT_CLASS_LOADER = UdfLoaderTest.class.getClassLoader();
  private static final Metrics METRICS = new Metrics();

  private static final FunctionRegistry FUNC_REG =
      initializeFunctionRegistry(true, Optional.empty());

  private static final FunctionRegistry FUNC_REG_WITH_METRICS =
      initializeFunctionRegistry(true, Optional.of(METRICS));

  private static final FunctionRegistry FUNC_REG_WITHOUT_CUSTOM =
      initializeFunctionRegistry(false, Optional.empty());

  private final KsqlConfig ksqlConfig = new KsqlConfig(Collections.emptyMap());

  private static final Schema STRUCT_SCHEMA =
      SchemaBuilder.struct().field("a", Schema.OPTIONAL_STRING_SCHEMA).build();

  @Rule
  public TemporaryFolder tempFolder = KsqlTestFolder.temporaryFolder();

  @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
  @Before
  public void before() {
    PASSED_CONFIG = null;
  }

  @Test
  public void shouldLoadFunctionsInKsqlEngine() {
    final UdfFactory function = FUNC_REG.getUdfFactory(FunctionName.of("substring"));
    assertThat(function, not(nullValue()));

    final Kudf substring1 = function.getFunction(
        Arrays.asList(SqlArgument.of(SqlTypes.STRING), SqlArgument.of(SqlTypes.INTEGER))).newInstance(ksqlConfig);
    assertThat(substring1.evaluate("foo", 2), equalTo("oo"));

    final Kudf substring2 = function.getFunction(
        Arrays.asList(SqlArgument.of(SqlTypes.STRING), SqlArgument.of(SqlTypes.INTEGER), SqlArgument.of(SqlTypes.INTEGER))).newInstance(ksqlConfig);
    assertThat(substring2.evaluate("foo", 2, 1), equalTo("o"));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldLoadUdafs() {
    final KsqlAggregateFunction instance = FUNC_REG
        .getAggregateFunction(FunctionName.of("test_udaf"), SqlTypes.BIGINT,
            AggregateFunctionInitArguments.EMPTY_ARGS);
    assertThat(instance.getInitialValueSupplier().get(), equalTo(0L));
    assertThat(instance.aggregate(1L, 1L), equalTo(2L));
    assertThat(instance.getMerger().apply(null, 2L, 3L), equalTo(5L));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void shouldLoadStructUdafs() {
    final Schema schema = SchemaBuilder.struct()
        .field("A", Schema.OPTIONAL_INT32_SCHEMA)
        .field("B", Schema.OPTIONAL_INT32_SCHEMA)
        .optional()
        .build();
    final SqlStruct sqlSchema = SqlTypes.struct()
        .field("A", SqlTypes.INTEGER)
        .field("B", SqlTypes.INTEGER)
        .build();

    final KsqlAggregateFunction instance = FUNC_REG
        .getAggregateFunction(FunctionName.of("test_udaf"), sqlSchema,
            AggregateFunctionInitArguments.EMPTY_ARGS);

    assertThat(instance.getInitialValueSupplier().get(),
        equalTo(new Struct(schema).put("A", 0).put("B", 0)));
    assertThat(instance.aggregate(
        new Struct(schema).put("A", 0).put("B", 0),
        new Struct(schema).put("A", 1).put("B", 2)
        ),
        equalTo(new Struct(schema).put("A", 1).put("B", 2)));
    assertThat(instance.getMerger().apply(null,
        new Struct(schema).put("A", 0).put("B", 0),
        new Struct(schema).put("A", 1).put("B", 2)
        ),
        equalTo(new Struct(schema).put("A", 1).put("B", 2)));
  }

  @Test
  public void shouldLoadDecimalUdfs() {
    // Given:
    final SqlDecimal schema = SqlTypes.decimal(2, 1);

    // When:
    final KsqlScalarFunction fun = FUNC_REG.getUdfFactory(FunctionName.of("floor"))
        .getFunction(ImmutableList.of(SqlArgument.of(schema)));

    // Then:
    assertThat(fun.name().text(), equalToIgnoringCase("floor"));
  }

  @Test
  public void shouldLoadLambdaReduceUdfs() {
    // Given:
    final SqlLambdaResolved lambda =
        SqlLambdaResolved.of(
            ImmutableList.of(SqlTypes.INTEGER, SqlTypes.INTEGER, SqlTypes.INTEGER),
            SqlTypes.INTEGER);

    // When:
    final KsqlScalarFunction fun = FUNC_REG.getUdfFactory(FunctionName.of("reduce"))
        .getFunction(
            ImmutableList.of(
                SqlArgument.of(SqlMap.of(SqlTypes.INTEGER, SqlTypes.INTEGER)),
                SqlArgument.of(SqlTypes.INTEGER),
                SqlArgument.of(lambda)));

    // Then:
    assertThat(fun.name().text(), equalToIgnoringCase("reduce"));
  }

  @Test
  public void shouldLoadLambdaTransformUdfs() {
    // Given:
    final SqlLambdaResolved lambda =
        SqlLambdaResolved.of(
            ImmutableList.of(SqlTypes.INTEGER),
            SqlTypes.INTEGER);

    // When:
    final KsqlScalarFunction fun = FUNC_REG.getUdfFactory(FunctionName.of("transform"))
        .getFunction(
            ImmutableList.of(
                SqlArgument.of(SqlArray.of(SqlTypes.INTEGER)),
                SqlArgument.of(lambda)));

    // Then:
    assertThat(fun.name().text(), equalToIgnoringCase("transform"));
  }

  @Test
  public void shouldLoadFunctionsFromJarsInPluginDir() {
    final UdfFactory toString = FUNC_REG.getUdfFactory(FunctionName.of("tostring"));
    final UdfFactory multi = FUNC_REG.getUdfFactory(FunctionName.of("multiply"));
    assertThat(toString, not(nullValue()));
    assertThat(multi, not(nullValue()));
  }

  @Test
  public void shouldLoadFunctionWithListReturnType() {
    // Given:
    final UdfFactory toList = FUNC_REG.getUdfFactory(FunctionName.of("tolist"));

    // When:
    final List<SqlArgument> args = Collections.singletonList(SqlArgument.of(SqlTypes.STRING));
    final KsqlScalarFunction function
        = toList.getFunction(args);

    assertThat(function.getReturnType(args),
        is(SqlTypes.array(SqlTypes.STRING))
    );
  }

  @Test
  public void shouldLoadFunctionWithMapReturnType() {
    // Given:
    final UdfFactory toMap = FUNC_REG.getUdfFactory(FunctionName.of("tomap"));

    // When:
    final List<SqlArgument> args = Collections.singletonList(SqlArgument.of(SqlTypes.STRING));
    final KsqlScalarFunction function
        = toMap.getFunction(args);

    // Then:
    assertThat(
        function.getReturnType(args),
        equalTo(SqlTypes.map(SqlTypes.STRING, SqlTypes.STRING))
    );
  }

  @Test
  public void shouldLoadFunctionWithStructReturnType() {
    // Given:
    final UdfFactory toStruct = FUNC_REG.getUdfFactory(FunctionName.of("tostruct"));

    // When:
    final List<SqlArgument> args = Collections.singletonList(SqlArgument.of(SqlTypes.STRING));
    final KsqlScalarFunction function
        = toStruct.getFunction(args);

    // Then:
    assertThat(function.getReturnType(args), equalTo(SqlTypes.struct()
        .field("A", SqlTypes.STRING)
        .build())
    );
  }

  @Test
  public void shouldLoadFunctionWithSchemaProvider() {
    // Given:
    final UdfFactory returnDecimal = FUNC_REG.getUdfFactory(FunctionName.of("returndecimal"));

    // When:
    final SqlDecimal decimal = SqlTypes.decimal(2, 1);
    final List<SqlArgument> args = Collections.singletonList(SqlArgument.of(decimal));
    final KsqlScalarFunction function = returnDecimal.getFunction(args);

    // Then:
    assertThat(function.getReturnType(args), equalTo(decimal));
  }

  @Test
  public void shouldLoadFunctionWithStructSchemaProvider() {
    // Given:
    final UdfFactory returnDecimal = FUNC_REG.getUdfFactory(FunctionName.of("KsqlStructUdf"));

    // When:
    final List<SqlArgument> args = ImmutableList.of();
    final KsqlScalarFunction function = returnDecimal.getFunction(args);

    // Then:
    assertThat(function.getReturnType(args), equalTo(KsqlStructUdf.RETURN));
  }


  @Test
  public void shouldLoadFunctionWithNestedDecimalSchema() {
    // Given:
    final UdfFactory returnDecimal = FUNC_REG.getUdfFactory(FunctionName.of("decimalstruct"));

    // When:
    final KsqlScalarFunction function = returnDecimal.getFunction(ImmutableList.of());

    // Then:
    assertThat(
        function.getReturnType(ImmutableList.of()),
        equalTo(SqlStruct.builder().field("VAL", SqlDecimal.of(64, 2)).build()));
  }

  @Test
  public void shouldThrowOnReturnTypeMismatch() {
    // Given:
    final UdfFactory returnIncompatible = FUNC_REG
        .getUdfFactory(of("returnincompatible"));
    final SqlDecimal decimal = decimal(2, 1);
    final List<SqlArgument> args = singletonList(SqlArgument.of(decimal));
    final KsqlScalarFunction function = returnIncompatible.getFunction(args);

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> function.getReturnType(args)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Return type DECIMAL(2, 1) of UDF RETURNINCOMPATIBLE does not "
            + "match the declared return type STRING."));
  }

  @Test
  public void shouldThrowOnMissingAnnotation() throws ClassNotFoundException {
    // Given:
    final MutableFunctionRegistry functionRegistry = new InternalFunctionRegistry();
    final Path udfJar = new File("src/test/resources/udf-failing-tests.jar").toPath();
    final UdfClassLoader udfClassLoader = newClassLoader(udfJar,
        PARENT_CLASS_LOADER,
        resourceName -> false);
    final Class<?> clazz = udfClassLoader.loadClass("org.damian.ksql.udf.MissingAnnotationUdf");
    final UdfLoader udfLoader = new UdfLoader(
        functionRegistry,
        empty(),
        create(EMPTY),
        true
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> udfLoader.loadUdfFromClass(clazz)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Cannot load UDF MissingAnnotation. DECIMAL return type is " +
            "not supported without an explicit schema"));

  }

  @Test
  public void shouldThrowOnMissingSchemaProvider() throws ClassNotFoundException {
    // Given:
    final MutableFunctionRegistry functionRegistry = new InternalFunctionRegistry();
    final Path udfJar = new File("src/test/resources/udf-failing-tests.jar").toPath();
    final UdfClassLoader udfClassLoader = newClassLoader(udfJar,
        PARENT_CLASS_LOADER,
        resourceName -> false);
    final Class<?> clazz = udfClassLoader.loadClass("org.damian.ksql.udf.MissingSchemaProviderUdf");
    final UdfLoader udfLoader = new UdfLoader(
        functionRegistry,
        empty(),
        create(EMPTY),
        true
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> udfLoader.loadUdfFromClass(clazz)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Cannot find schema provider method with name provideSchema "
            + "and parameter List<SqlType> in class org.damian.ksql.udf."
            + "MissingSchemaProviderUdf."));
  }

  @Test
  public void shouldThrowOnReturnDecimalWithoutSchemaProvider() throws ClassNotFoundException {
    // Given:
    final MutableFunctionRegistry functionRegistry = new InternalFunctionRegistry();
    final Path udfJar = new File("src/test/resources/udf-failing-tests.jar").toPath();
    final UdfClassLoader udfClassLoader = newClassLoader(udfJar,
        PARENT_CLASS_LOADER,
        resourceName -> false);
    final Class<?> clazz = udfClassLoader.loadClass("org.damian.ksql.udf."
        + "ReturnDecimalWithoutSchemaProviderUdf");
    final UdfLoader udfLoader = new UdfLoader(
        functionRegistry,
        empty(),
        create(EMPTY),
        true
    );

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> udfLoader.loadUdfFromClass(clazz)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Cannot load UDF ReturnDecimalWithoutSchemaProvider. DECIMAL return type is not " +
            "supported without an explicit schema"));
  }

  @Test
  public void shouldPutJarUdfsInClassLoaderForJar() throws Exception {
    final UdfFactory toString = FUNC_REG.getUdfFactory(FunctionName.of("tostring"));
    final UdfFactory multiply = FUNC_REG.getUdfFactory(FunctionName.of("multiply"));

    final Kudf toStringUdf = toString.getFunction(ImmutableList.of(SqlArgument.of(SqlTypes.STRING)))
        .newInstance(ksqlConfig);

    final Kudf multiplyUdf = multiply.getFunction(
        Arrays.asList(SqlArgument.of(SqlTypes.INTEGER), SqlArgument.of(SqlTypes.INTEGER)))
        .newInstance(ksqlConfig);

    final ClassLoader multiplyLoader = getActualUdfClassLoader(multiplyUdf);
    assertThat(multiplyLoader, equalTo(getActualUdfClassLoader(toStringUdf)));
    assertThat(multiplyLoader, not(equalTo(PARENT_CLASS_LOADER)));
  }

  @Test
  public void shouldAllowClassesWithSameFQCNInDifferentUDFJars() throws Exception {

    final File pluginDir = tempFolder.newFolder();
    Files.copy(Paths.get("src/test/resources/udf-example.jar"),
        new File(pluginDir, "udf-example.jar").toPath());
    Files.copy(Paths.get("src/test/resources/udf-isolated.jar"),
        new File(pluginDir, "udf-isolated.jar").toPath());

    final MutableFunctionRegistry functionRegistry = new InternalFunctionRegistry();
    final UserFunctionLoader udfLoader = new UserFunctionLoader(
        functionRegistry,
        pluginDir,
        PARENT_CLASS_LOADER,
        value -> false,
        Optional.empty(),
        true);

    udfLoader.load();

    final UdfFactory multiply = functionRegistry.getUdfFactory(FunctionName.of("multiply"));
    final UdfFactory multiply2 = functionRegistry.getUdfFactory(FunctionName.of("multiply2"));

    final Kudf multiplyUdf = multiply.getFunction(Arrays.asList(SqlArgument.of(
        SqlTypes.INTEGER),
        SqlArgument.of(SqlTypes.INTEGER))
    ).newInstance(ksqlConfig);

    final Kudf multiply2Udf = multiply2
        .getFunction(Arrays.asList(SqlArgument.of(SqlTypes.INTEGER), SqlArgument.of(SqlTypes.INTEGER)))
        .newInstance(ksqlConfig);

    assertThat(multiplyUdf.evaluate(2, 2), equalTo(4L));
    assertThat(multiply2Udf.evaluate(2, 2), equalTo(5L));
  }

  @Test
  public void shouldCreateUdfFactoryWithJarPathWhenExternal() {
    final UdfFactory tostring = FUNC_REG.getUdfFactory(FunctionName.of("tostring"));
    String expectedPath = Arrays.asList("src", "test", "resources", "udf-example.jar")
        .stream()
        .collect(Collectors.joining(File.separator));
    assertThat(tostring.getMetadata().getPath(), equalTo(expectedPath));
  }

  @Test
  public void shouldCreateUdfFactoryWithInternalPathWhenInternal() {
    final UdfFactory substring = FUNC_REG.getUdfFactory(FunctionName.of("substring"));
    assertThat(substring.getMetadata().getPath(), equalTo(KsqlScalarFunction.INTERNAL_PATH));
  }

  @Test
  public void shouldSupportUdfParameterAnnotation() {
    final UdfFactory substring = FUNC_REG.getUdfFactory(FunctionName.of("somefunction"));
    final KsqlScalarFunction function = substring.getFunction(
        ImmutableList.of(
            SqlArgument.of(SqlTypes.STRING),
            SqlArgument.of(SqlTypes.STRING),
            SqlArgument.of(SqlTypes.STRING)));

    final List<ParameterInfo> arguments = function.parameterInfo();

    assertThat(arguments.get(0).name(), is("justValue"));
    assertThat(arguments.get(0).description(), is(""));
    assertThat(arguments.get(1).name(), is("valueAndDescription"));
    assertThat(arguments.get(1).description(), is("Some description"));
    // NB: Is the below failing?
    // Then you need to add `-parameters` to your IDE's java compiler settings.
    assertThat(arguments.get(2).name(), is("noValue"));
    assertThat(arguments.get(2).description(), is(""));
  }

  @Test
  public void shouldPutKsqlFunctionsInParentClassLoader() throws Exception {
    final UdfFactory substring = FUNC_REG.getUdfFactory(FunctionName.of("substring"));
    final Kudf kudf = substring.getFunction(
        Arrays.asList(SqlArgument.of(SqlTypes.STRING), SqlArgument.of(SqlTypes.INTEGER)))
        .newInstance(ksqlConfig);
    assertThat(getActualUdfClassLoader(kudf), equalTo(PARENT_CLASS_LOADER));
  }

  @Test
  public void shouldLoadUdfsInKSQLIfLoadCustomerUdfsFalse() {
    // udf in ksql-engine will throw if not found
    FUNC_REG_WITHOUT_CUSTOM.getUdfFactory(FunctionName.of("substring"));
  }

  @Test
  public void shouldNotLoadCustomUDfsIfLoadCustomUdfsFalse() {
    // udf in udf-example.jar
    try {
      FUNC_REG_WITHOUT_CUSTOM.getUdfFactory(FunctionName.of("tostring"));
      fail("Should have thrown as function doesn't exist");
    } catch (final KsqlException e) {
      // pass
    }
  }

  @Test
  public void shouldNotLoadInternalUdfs() {
    // Given:
    final MutableFunctionRegistry functionRegistry = new InternalFunctionRegistry();
    final UdfLoader udfLoader = new UdfLoader(
        functionRegistry,
        empty(),
        create(EMPTY),
        true
    );
    udfLoader.loadUdfFromClass(SomeFunctionUdf.class);

    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> functionRegistry.getUdfFactory(of("substring"))
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Can't find any functions with the name 'substring'"));
  }

  @Test
  public void shouldLoadSomeFunction() {
    // Given:
    final MutableFunctionRegistry functionRegistry = new InternalFunctionRegistry();
    final UdfLoader udfLoader = new UdfLoader(
        functionRegistry,
        Optional.empty(),
        SqlTypeParser.create(TypeRegistry.EMPTY),
        true
    );
    final ImmutableList<SqlArgument> args = ImmutableList.of(
        SqlArgument.of(SqlTypes.STRING),
        SqlArgument.of(SqlTypes.STRING),
        SqlArgument.of(SqlTypes.STRING));

    // When:
    udfLoader.loadUdfFromClass(UdfLoaderTest.SomeFunctionUdf.class);
    final UdfFactory udfFactory = functionRegistry.getUdfFactory(FunctionName.of("somefunction"));

    // Then:
    assertThat(udfFactory, not(nullValue()));
    final KsqlScalarFunction function = udfFactory.getFunction(args);
    assertThat(function.name().text(), equalToIgnoringCase("somefunction"));

  }

  @Test
  public void shouldCollectMetricsWhenMetricCollectionEnabled() {
    // Given:
    final UdfFactory substring = FUNC_REG_WITH_METRICS.getUdfFactory(FunctionName.of("substring"));
    final KsqlScalarFunction function = substring
        .getFunction(Arrays.asList(SqlArgument.of(SqlTypes.STRING), SqlArgument.of(SqlTypes.INTEGER)));

    // When:
    final Kudf kudf = function.newInstance(ksqlConfig);

    // Then:
    assertThat(kudf, instanceOf(UdfMetricProducer.class));
    final Sensor sensor = METRICS.getSensor("ksql-udf-substring");
    assertThat(sensor, not(nullValue()));
    assertThat(METRICS.metric(METRICS.metricName("ksql-udf-substring-count", "ksql-udf")),
        not(nullValue()));
    assertThat(METRICS.metric(METRICS.metricName("ksql-udf-substring-max", "ksql-udf")),
        not(nullValue()));
    assertThat(METRICS.metric(METRICS.metricName("ksql-udf-substring-avg", "ksql-udf")),
        not(nullValue()));
    assertThat(METRICS.metric(METRICS.metricName("ksql-udf-substring-rate", "ksql-udf")),
        not(nullValue()));
  }

  @Test
  public void shouldUseConfigForExtDir() {
    final InternalFunctionRegistry functionRegistry = new InternalFunctionRegistry();
    // The tostring function is in the udf-example.jar that is found in src/test/resources
    final ImmutableMap<Object, Object> configMap
        = ImmutableMap.builder().put(KsqlConfig.KSQL_EXT_DIR, "src/test/resources")
        .put(KsqlConfig.KSQL_UDF_SECURITY_MANAGER_ENABLED, false)
        .build();
    final KsqlConfig config
        = new KsqlConfig(configMap);
    UserFunctionLoader.newInstance(config, functionRegistry, "").load();
    // will throw if it doesn't exist
    functionRegistry.getUdfFactory(FunctionName.of("tostring"));
  }

  @Test
  public void shouldNotThrowWhenExtDirDoesntExist() {
    final ImmutableMap<Object, Object> configMap
        = ImmutableMap.builder().put(KsqlConfig.KSQL_EXT_DIR, "foo/bar")
        .put(KsqlConfig.KSQL_UDF_SECURITY_MANAGER_ENABLED, false)
        .build();
    final KsqlConfig config
        = new KsqlConfig(configMap);
    UserFunctionLoader.newInstance(config, new InternalFunctionRegistry(), "").load();
  }

  @Test
  public void shouldConfigureConfigurableUdfsOnInstantiation() {
    // Given:
    final KsqlConfig ksqlConfig = new KsqlConfig(ImmutableMap.of(
        KsqlConfig.KSQL_SERVICE_ID_CONFIG, "should not be passed",
        KSQL_FUNCTIONS_PROPERTY_PREFIX + "configurableudf.some.setting", "foo-bar",
        KSQL_FUNCTIONS_PROPERTY_PREFIX + "_global_.expected-param", "expected-value"
    ));

    final KsqlScalarFunction udf = FUNC_REG.getUdfFactory(FunctionName.of("ConfigurableUdf"))
        .getFunction(ImmutableList.of(SqlArgument.of(SqlTypes.INTEGER)));

    // When:
    udf.newInstance(ksqlConfig);

    // Then:
    assertThat(PASSED_CONFIG, is(notNullValue()));
    assertThat(PASSED_CONFIG.keySet(), not(hasItem(KsqlConfig.KSQL_SERVICE_ID_CONFIG)));
    assertThat(PASSED_CONFIG.get(KSQL_FUNCTIONS_PROPERTY_PREFIX + "configurableudf.some.setting"),
        is("foo-bar"));
    assertThat(PASSED_CONFIG.get(KSQL_FUNCTIONS_PROPERTY_PREFIX + "_global_.expected-param"),
        is("expected-value"));
  }

  @Test
  public void shouldEnsureFunctionReturnTypeIsDeepOptional() {
    final List<SqlArgument> args = Collections.singletonList(SqlArgument.of(SqlTypes.STRING));
    final KsqlScalarFunction complexFunction = FUNC_REG
        .getUdfFactory(FunctionName.of("ComplexFunction"))
        .getFunction(args);

    assertThat(complexFunction.getReturnType(args), is(
        SqlTypes.struct()
            .field("F0", SqlTypes.struct()
                .field("F1", SqlTypes.INTEGER)
                .build())
            .build()));
  }

  @Test
  public void shouldInvokeFunctionWithMapArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udf", Map.class));
    assertThat(udf.eval(this, Collections.emptyMap()), equalTo("{}"));
  }

  @Test
  public void shouldInvokeFunctionWithListArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udf", List.class));
    assertThat(udf.eval(this, Collections.emptyList()), equalTo("[]"));
  }

  @Test
  public void shouldInvokeFunctionWithDoubleArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udf", Double.class));
    assertThat(udf.eval(this, 1.0d), equalTo(1.0));
  }

  @Test
  public void shouldInvokeFunctionWithIntegerArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udf", Integer.class));
    assertThat(udf.eval(this, 1), equalTo(1));
  }

  @Test
  public void shouldInvokeFunctionWithLongArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udf", Long.class));
    assertThat(udf.eval(this, 1L), equalTo(1L));
  }

  @Test
  public void shouldInvokeFunctionWithBooleanArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udf", Boolean.class));
    assertThat(udf.eval(this, true), equalTo(true));
  }

  @Test
  public void shouldInvokeFunctionWithIntArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udfPrimitive", int.class));
    assertThat(udf.eval(this, 1), equalTo(1));
  }

  @Test
  public void shouldInvokeFunctionWithIntVarArgs() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udfPrimitive", int[].class));
    assertThat(udf.eval(this, 1, 1), equalTo(2));
  }

  @Test
  public void shouldInvokeFunctionWithPrimitiveLongArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udfPrimitive", long.class));
    assertThat(udf.eval(this, 1), equalTo(1L));
  }

  @Test
  public void shouldInvokeFunctionWithPrimitiveDoubleArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udfPrimitive", double.class));
    assertThat(udf.eval(this, 1), equalTo(1.0));
  }

  @Test
  public void shouldInvokeFunctionWithPrimitiveBooleanArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udfPrimitive", boolean.class));
    assertThat(udf.eval(this, true), equalTo(true));
  }

  @Test
  public void shouldInvokeFunctionWithStringArgument() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udf", String.class));
    assertThat(udf.eval(this, "foo"), equalTo("foo"));
  }

  @Test
  public void shouldInvokeFunctionWithStringVarArgs() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udf", String[].class));
    assertThat(udf.eval(this, "foo", "bar"), equalTo("foobar"));
  }

  @Test
  public void shouldHandleMethodsWithMultipleArguments() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils.createFunctionInvoker(
        getClass().getMethod("multi", int.class, long.class, double.class));

    assertThat(udf.eval(this, 1, 2, 3), equalTo(6.0));
  }

  @Test
  public void shouldHandleMethodsWithGenericArguments() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils.createFunctionInvoker(
        getClass().getMethod("generic", int.class, Object.class));

    assertThat(udf.eval(this, 1, "hi"), equalTo("hi"));
  }

  @Test
  public void shouldHandleMethodsWithParameterizedGenericArguments() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils.createFunctionInvoker(
        getClass().getMethod("generic", int.class, List.class));

    assertThat(udf.eval(this, 1, ImmutableList.of("hi")), equalTo("hi"));
  }

  @Test
  public void shouldInvokeUdafWithMethodWithNoArgs() throws Exception {
    final UdafFactoryInvoker creator
        = createUdafLoader().createUdafFactoryInvoker(
        TestUdaf.class.getMethod("createSumLong"),
        FunctionName.of("test-udf"),
        "desc",
        "",
        "",
        "");
    assertThat(creator.createFunction(AggregateFunctionInitArguments.EMPTY_ARGS),
        not(nullValue()));
  }

  @Test
  public void shouldConfigureConfigurableUdaf() throws Exception {
    // Given:
    final UdafFactoryInvoker creator
        = createUdafLoader().createUdafFactoryInvoker(
        TestUdaf.class.getMethod("createSumInt"),
        FunctionName.of("test-udf"),
        "desc",
        "",
        "",
        "");
    final AggregateFunctionInitArguments initArgs = new AggregateFunctionInitArguments(
        0, ImmutableMap.of("ksql.functions.test_udaf.init", 100L));

    // When:
    final KsqlAggregateFunction function = creator.createFunction(initArgs);
    final Object initvalue = function.getInitialValueSupplier().get();

    // Then:
    assertThat(initvalue, is(100L));
  }

  @Test
  public void shouldInvokeFunctionWithStructReturnValue() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udfStruct", String.class));
    assertThat(udf.eval(this, "val"), equalTo(new Struct(STRUCT_SCHEMA).put("a", "val")));
  }

  @Test
  public void shouldInvokeFunctionWithStructParameter() throws Exception {
    final FunctionInvoker udf = FunctionLoaderUtils
        .createFunctionInvoker(getClass().getMethod("udfStruct", Struct.class));
    assertThat(udf.eval(this, new Struct(STRUCT_SCHEMA).put("a", "val")), equalTo("val"));
  }

  @Test
  public void shouldImplementTableAggregateFunctionWhenTableUdafClass() throws Exception {
    final UdafFactoryInvoker creator
        = createUdafLoader().createUdafFactoryInvoker(
        TestUdaf.class.getMethod("createSumLong"),
        FunctionName.of("test-udf"),
        "desc",
        "",
        "",
        "");
    final KsqlAggregateFunction function = creator
        .createFunction(AggregateFunctionInitArguments.EMPTY_ARGS);
    assertThat(function, instanceOf(TableAggregationFunction.class));
  }

  @Test
  public void shouldInvokeUdafWhenMethodHasArgs() throws Exception {
    final UdafFactoryInvoker creator
        = createUdafLoader().createUdafFactoryInvoker(
        TestUdaf.class.getMethod(
            "createSumLengthString",
            String.class),
        FunctionName.of("test-udf"),
        "desc",
        "",
        "",
        "");
    final KsqlAggregateFunction instance =
        creator.createFunction(new AggregateFunctionInitArguments(0, "foo"));
    assertThat(instance,
        not(nullValue()));
    assertThat(instance, not(instanceOf(TableAggregationFunction.class)));
  }

  @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
  @SuppressWarnings("unchecked")
  @Test
  public void shouldCollectMetricsForUdafsWhenEnabled() throws Exception {
    final Metrics metrics = new Metrics();
    final UdafFactoryInvoker creator
        = createUdafLoader(Optional.of(metrics)).createUdafFactoryInvoker(
        TestUdaf.class.getMethod("createSumLong"),
        FunctionName.of("test-udf"),
        "desc",
        "",
        "",
        "");

    final KsqlAggregateFunction<Long, Long, Long> executable =
        creator.createFunction(AggregateFunctionInitArguments.EMPTY_ARGS);

    executable.aggregate(1L, 1L);
    executable.aggregate(1L, 1L);
    final KafkaMetric metric = metrics.metric(
        metrics.metricName("aggregate-test-udf-createSumLong-count",
            "ksql-udaf-test-udf-createSumLong"));
    assertThat(metric.metricValue(), equalTo(2.0));
  }

  @Test(expected = KsqlException.class)
  public void shouldThrowIfUnsupportedArgumentType() throws Exception {
    FunctionLoaderUtils.createFunctionInvoker(
        getClass().getMethod("udf", Set.class));
  }

  @Test(expected = KsqlException.class)
  public void shouldThrowIfUnsupportedInputType() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("invalidInputTypeUdaf"),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        ""
    );
  }

  @Test
  public void shouldThrowIfMissingInputTypeSchema() throws Exception {
    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> createUdafLoader().createUdafFactoryInvoker(
            UdfLoaderTest.class.getMethod("missingInputSchemaAnnotationUdaf"),
            of("test"),
            "desc",
            "",
            "",
            "")
    );

    // Then:
    assertThat(e.getMessage(), containsString("Must specify 'paramSchema' for STRUCT parameter in @UdafFactory."));
  }

  @Test
  public void shouldThrowIfMissingAggregateTypeSchema() throws Exception {
    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> createUdafLoader().createUdafFactoryInvoker(
            UdfLoaderTest.class.getMethod("missingAggregateSchemaAnnotationUdaf"),
            of("test"),
            "desc",
            "",
            "",
            "")
    );

    // Then:
    assertThat(e.getMessage(), containsString("Must specify 'aggregateSchema' for STRUCT parameter in @UdafFactory."));
  }

  @Test
  public void shouldThrowIfMissingOutputTypeSchema() throws Exception {
    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> createUdafLoader().createUdafFactoryInvoker(
            UdfLoaderTest.class.getMethod("missingOutputSchemaAnnotationUdaf"),
            of("test"),
            "desc",
            "",
            "",
            ""
        )
    );

    // Then:
    assertThat(e.getMessage(), containsString("Must specify 'returnSchema' for STRUCT parameter in @UdafFactory."));
  }

  @Test
  public void shouldThrowIfArrayWithoutVarArgs() throws Exception {
    // When:
    final Exception e = assertThrows(
        KsqlFunctionException.class,
        () -> createFunctionInvoker(
            getClass().getMethod("invalidUdf", int[].class))
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Invalid function method signature (contains non var-arg array)"));
  }

  @Test
  public void shouldThrowIfArrayAndVarArgs() throws Exception {
    // When:
    final Exception e = assertThrows(
        KsqlFunctionException.class,
        () -> createFunctionInvoker(
            getClass().getMethod("invalidUdf", int[].class, int[].class))
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Invalid function method signature (contains non var-arg array):"));
  }

  @Test
  public void shouldThrowKsqlFunctionExceptionIfNullPassedWhenExpectingPrimitiveType()
      throws Exception {
    final FunctionInvoker udf =
        createFunctionInvoker(getClass().getMethod("udfPrimitive", double.class));

    // When:
    final Exception e = assertThrows(
        KsqlFunctionException.class,
        () -> udf.eval(this, (Double) null)
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "Failed to invoke function"));
  }

  @Test
  public void shouldThrowWhenUdafReturnTypeIsntAUdaf() throws Exception {
    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> createUdafLoader().createUdafFactoryInvoker(
            UdfLoaderTest.class.getMethod("createBlah"),
            of("test"),
            "desc",
            "",
            "",
            "")
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "UDAFs must implement io.confluent.ksql.function.udaf.Udaf or io.confluent.ksql.function.udaf.TableUdaf. method='createBlah', functionName='`test`', UDFClass='class io.confluent.ksql.function.UdfLoaderTest"));
  }

  @Test
  public void shouldHandleUdafsWithLongValTypeDoubleAggType() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createLongDouble"),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        "");
  }

  @Test
  public void shouldHandleUdafsWithDoubleValTypeLongAggType() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createDoubleLong"),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        "");
  }

  @Test
  public void shouldHandleUdafsWithIntegerValTypeStringAggType() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createIntegerString"),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        "");
  }

  @Test
  public void shouldHandleUdafsWithStringValTypeIntegerAggType() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createStringInteger"),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        "");
  }

  @Test
  public void shouldHandleUdafsWithBooleanValTypeListAggType() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createBooleanList"),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        "");
  }

  @Test
  public void shouldHandleUdafsWithListValTypeBooleanAggType() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createListBoolean"),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        "");
  }

  @Test
  public void shouldHandleUdafsWithMapValMapAggTypes() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createMapMap"),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        "");
  }

  @Test
  public void shouldHandleUdafsWithMapValMapAggTypesAndFactoryArg() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createMapMap", int.class),
        FunctionName.of("test"),
        "desc",
        "",
        "",
        "");
  }

  @Test
  public void shouldHandleUdafsWithStructStructTypes() throws Exception {
    createUdafLoader().createUdafFactoryInvoker(
        UdfLoaderTest.class.getMethod("createStructStruct"),
        FunctionName.of("test"),
        "desc",
        "STRUCT<A VARCHAR>",
        "STRUCT<B VARCHAR>",
        "STRUCT<B VARCHAR>");
  }

  @Test
  public void shouldThrowWhenTryingToGenerateUdafThatHasIncorrectTypes() throws Exception {
    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> createUdafLoader().createUdafFactoryInvoker(
            UdfLoaderTest.class.getMethod("createBad"),
            of("test"),
            "desc",
            "",
            "",
            "")
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "class='class java.lang.Character' is not supported by UDAFs"));
  }

  @Test
  public void shouldThrowWhenUdafFactoryMethodIsntStatic() throws Exception {
    // When:
    final Exception e = assertThrows(
        KsqlException.class,
        () -> createUdafLoader().createUdafFactoryInvoker(
            UdfLoaderTest.class.getMethod("createNonStatic"),
            of("test"),
            "desc",
            "",
            "",
            "")
    );

    // Then:
    assertThat(e.getMessage(), containsString(
        "UDAF factory methods must be static public io.confluent.ksql.function.udaf.Udaf"));
  }

  public String udf(final Set val) {
    return val.toString();
  }

  public String udf(final Map<String, Integer> map) {
    return map.toString();
  }

  public String udf(final List<String> list) {
    return list.toString();
  }

  public Double udf(final Double val) {
    return val;
  }

  public Float udf(final Float val) {
    return val;
  }

  public Integer udf(final Integer val) {
    return val;
  }

  public Long udf(final Long val) {
    return val;
  }

  public <T> T generic(final int foo, final T val) {
    return val;
  }

  public <T> T generic(final int foo, final List<T> val) {
    return val.get(0);
  }

  public Struct udfStruct(final String val) {
    return new Struct(STRUCT_SCHEMA).put("a", val);
  }

  public String udfStruct(final Struct struct) {
    return struct.getString("a");
  }

  public double udfPrimitive(final double val) {
    return val;
  }

  public float udfPrimitive(final float val) {
    return val;
  }

  public int udfPrimitive(final int val) {
    return val;
  }

  public int udfPrimitive(final int... val) {
    return Arrays.stream(val).sum();
  }

  public long udfPrimitive(final long val) {
    return val;
  }

  public boolean udfPrimitive(final boolean val) {
    return val;
  }

  public Boolean udf(final Boolean val) {
    return val;
  }

  public String udf(final String val) {
    return val;
  }

  public String udf(final String... val) {
    return String.join("", val);
  }

  public double multi(final int i, final long l, final double d) {
    return i * l * d;
  }

  public static Udaf<Long, Double, Double> createLongDouble() {
    return null;
  }

  public static Udaf<Double, Long, Long> createDoubleLong() {
    return null;
  }

  public static Udaf<Integer, String, String> createIntegerString() {
    return null;
  }

  public static Udaf<String, Integer, Integer> createStringInteger() {
    return null;
  }

  public static Udaf<Boolean, List<Long>, List<Long>> createBooleanList() {
    return null;
  }

  public static Udaf<List<Integer>, Boolean, Boolean> createListBoolean() {
    return null;
  }

  public static Udaf<Map<String, Integer>, Map<String, Boolean>, Map<String, Boolean>> createMapMap() {
    return null;
  }

  public static Udaf<Map<String, Integer>, Map<String, Boolean>, Map<String, Boolean>> createMapMap(
      final int ignored) {
    return null;
  }

  public static Udaf<Struct, Struct, Struct> createStructStruct() {
    return null;
  }

  public static String createBlah() {
    return null;
  }

  public static Udaf<Character, Character, Character> createBad() {
    return null;
  }

  public Udaf<String, String, String> createNonStatic() {
    return null;
  }

  public static String invalidUdf(final int[] ints) {
    return null;
  }

  public static String invalidUdf(final int[] ints, final int... moreInts) {
    return null;
  }

  public static Udaf<List<?>, String, String> invalidInputTypeUdaf() {
    return null;
  }

  public static Udaf<Struct, String, String> missingInputSchemaAnnotationUdaf() {
    return null;
  }

  public static Udaf<String, Struct, String> missingAggregateSchemaAnnotationUdaf() {
    return null;
  }

  public static Udaf<String, String, Struct> missingOutputSchemaAnnotationUdaf() {
    return null;
  }

  private static UdafLoader createUdafLoader() {
    return createUdafLoader(Optional.empty());
  }

  private static UdafLoader createUdafLoader(final Optional<Metrics> metrics) {
    return new UdafLoader(new InternalFunctionRegistry(), metrics, SqlTypeParser.create(
        TypeRegistry.EMPTY));
  }

  private static FunctionRegistry initializeFunctionRegistry(
      final boolean loadCustomUdfs,
      final Optional<Metrics> metrics
  ) {
    final MutableFunctionRegistry functionRegistry = new InternalFunctionRegistry();
    final UserFunctionLoader pluginLoader = createUdfLoader(
        functionRegistry, loadCustomUdfs, metrics);
    pluginLoader.load();
    return functionRegistry;
  }

  private static UserFunctionLoader createUdfLoader(
      final MutableFunctionRegistry functionRegistry,
      final boolean loadCustomerUdfs,
      final Optional<Metrics> metrics
  ) {
    return new UserFunctionLoader(
        functionRegistry,
        new File("src/test/resources/udf-example.jar"),
        PARENT_CLASS_LOADER,
        value -> false,
        metrics,
        loadCustomerUdfs
    );
  }

  private static ClassLoader getActualUdfClassLoader(final Kudf udf) throws Exception {
    final Field actualUdf = PluggableUdf.class.getDeclaredField("actualUdf");
    actualUdf.setAccessible(true);
    return actualUdf.get(udf).getClass().getClassLoader();
  }

  @SuppressWarnings({"unused", "MethodMayBeStatic"}) // Invoked via reflection in test.
  public static class UdfWithMissingDescriptionAnnotation {
    @Udf(description = "This invalid UDF is here to test that the loader does not blow up if badly"
        + " formed UDFs are in the class path.")
    public String something(final String value) {
      return null;
    }
  }

  private static Map<String, ?> PASSED_CONFIG = null;

  @SuppressWarnings({"unused", "MethodMayBeStatic"}) // Invoked via reflection in test.
  @UdfDescription(
      name = "ConfigurableUdf",
      description = "A test-only UDF for testing configure() is called")
  public static class ConfigurableUdf implements Configurable {
    @Override
    public void configure(final Map<String, ?> map) {
      PASSED_CONFIG = map;
    }

    @Udf
    public int foo(final int bar) {
      return bar;
    }
  }

  @SuppressWarnings({"unused", "MethodMayBeStatic"}) // Invoked via reflection in test.
  @UdfDescription(
      name = "SomeFunction",
      description = "A test-only UDF for testing 'UdfParameter'")
  public static class SomeFunctionUdf {
    @Udf
    public int foo(
        @UdfParameter("justValue") final String v0,
        @UdfParameter(value = "valueAndDescription", description = "Some description") final String v1,
        @UdfParameter final String noValue) {
      return 0;
    }
  }

  @SuppressWarnings({"unused", "MethodMayBeStatic"}) // Invoked via reflection in test.
  @UdfDescription(
      name = "ComplexFunction",
      description = "A test-only UDF that uses the 'schema' parameter")
  public static class ComplexUdf {

    @Udf(schema = "STRUCT<f0 STRUCT<f1 INT>>")
    public Object foo(final String noValue) {
      return 0;
    }
  }

  @SuppressWarnings({"unused", "MethodMayBeStatic"}) // Invoked via reflection in test.
  @UdfDescription(
      name = "ReturnDecimal",
      description = "A test-only UDF for testing 'SchemaProvider'")

  public static class ReturnDecimalUdf {

    @Udf(schemaProvider = "provideSchema")
    public BigDecimal foo(@UdfParameter("justValue") final BigDecimal p) {
      return p;
    }

    @UdfSchemaProvider
    public SqlType provideSchema(final List<SqlType> params) {
      return SqlDecimal.of(2, 1);
    }
  }

  @UdfDescription(
      name = "DecimalStruct",
      description = "A test-only UDF for testing nested DECIMAL in schema annotation")
  public static class DecimalStructUdf {

    @Udf(schema = "STRUCT<VAL DECIMAL(64,2)>")
    public Struct getDecimalStruct() {
      final Schema schema = SchemaBuilder.struct()
          .optional()
          .field("VAL",
              Decimal.builder(2).optional().parameter("connect.decimal.precision", "64").build())
          .build();

      Struct struct = new Struct(schema);
      struct.put("VAL", BigDecimal.valueOf(123.45).setScale(2, RoundingMode.CEILING));
      return struct;
    }
  }

  @UdfDescription(
      name = "KsqlStructUdf",
      description = "A test-only UDF for testing struct return types")
  public static class KsqlStructUdf {

    private static final SqlStruct RETURN =
        SqlStruct.builder().field("VAL", SqlTypes.STRING).build();

    @UdfSchemaProvider
    public SqlType provide(final List<SqlType> params) {
      return RETURN;
    }

    @Udf(schemaProvider = "provide")
    public Struct getDecimalStruct() {
      return null;
    }
  }

  @SuppressWarnings({"unused", "MethodMayBeStatic"}) // Invoked via reflection in test.
  @UdfDescription(
      name = "ReturnIncompatible",
      description = "A test-only UDF for testing 'SchemaProvider'")

  public static class ReturnIncompatibleUdf {

    @Udf(schemaProvider = "provideSchema")
    public String foo(@UdfParameter("justValue") final BigDecimal p) {
      return "lala";
    }

    @UdfSchemaProvider
    public SqlType provideSchema(final List<Schema> params) {
      return SqlDecimal.of(2, 1);
    }
  }

}