/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.tests;

import com.facebook.presto.Session;
import com.facebook.presto.common.type.LongEnumParametricType;
import com.facebook.presto.common.type.LongEnumType.LongEnumMap;
import com.facebook.presto.common.type.ParametricType;
import com.facebook.presto.common.type.VarcharEnumParametricType;
import com.facebook.presto.common.type.VarcharEnumType.VarcharEnumMap;
import com.facebook.presto.spi.Plugin;
import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.testing.MaterializedRow;
import com.facebook.presto.testing.QueryRunner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.util.List;
import java.util.stream.Collectors;

import static com.facebook.presto.testing.TestingSession.testSessionBuilder;
import static java.util.Collections.singletonList;
import static org.testng.Assert.assertEquals;

@Test(singleThreaded = true)
public class TestEnums
        extends AbstractTestQueryFramework
{
    private static final Long BIG_VALUE = Integer.MAX_VALUE + 10L; // 2147483657

    private static final LongEnumParametricType MOOD_ENUM = new LongEnumParametricType("test.enum.Mood", new LongEnumMap(ImmutableMap.of(
            "HAPPY", 0L,
            "SAD", 1L,
            "MELLOW", BIG_VALUE,
            "curious", -2L)));
    private static final VarcharEnumParametricType COUNTRY_ENUM = new VarcharEnumParametricType("test.enum.Country", new VarcharEnumMap(ImmutableMap.of(
            "US", "United States",
            "BAHAMAS", "The Bahamas",
            "FRANCE", "France",
            "CHINA", "中国",
            "भारत", "India")));
    private static final VarcharEnumParametricType TEST_ENUM = new VarcharEnumParametricType("TestEnum", new VarcharEnumMap(ImmutableMap.of(
            "TEST", "\"}\"",
            "TEST2", "",
            "TEST3", " ",
            "TEST4", ")))\"\"")));
    private static final LongEnumParametricType TEST_LONG_ENUM = new LongEnumParametricType("TestLongEnum", new LongEnumMap(ImmutableMap.of(
            "TEST", 6L,
            "TEST2", 8L)));

    static class TestEnumPlugin
            implements Plugin
    {
        @Override
        public Iterable<ParametricType> getParametricTypes()
        {
            return ImmutableList.of(MOOD_ENUM, COUNTRY_ENUM, TEST_ENUM, TEST_LONG_ENUM);
        }
    }

    protected TestEnums()
    {
        super(TestEnums::createQueryRunner);
    }

    private static QueryRunner createQueryRunner()
    {
        try {
            Session session = testSessionBuilder().build();
            QueryRunner queryRunner = DistributedQueryRunner.builder(session).setNodeCount(1).build();
            queryRunner.installPlugin(new TestEnumPlugin());
            return queryRunner;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertQueryResultUnordered(@Language("SQL") String query, List<List<Object>> expectedRows)
    {
        MaterializedResult rows = computeActual(query);
        assertEquals(
                ImmutableSet.copyOf(rows.getMaterializedRows()),
                expectedRows.stream().map(row -> new MaterializedRow(1, row)).collect(Collectors.toSet()));
    }

    private void assertSingleValue(@Language("SQL") String expression, Object expectedResult)
    {
        assertQueryResultUnordered("SELECT " + expression, singletonList(singletonList(expectedResult)));
    }

    @Test
    public void testEnumLiterals()
    {
        assertQueryResultUnordered(
                "SELECT test.enum.mood.HAPPY, test.enum.mood.happY, \"test.enum.mood\".SAD, \"test.enum.mood\".\"mellow\"",
                singletonList(ImmutableList.of(0L, 0L, 1L, BIG_VALUE)));

        assertQueryResultUnordered(
                "SELECT test.enum.country.us, test.enum.country.\"CHINA\", test.enum.country.\"भारत\"",
                singletonList(ImmutableList.of("United States", "中国", "India")));

        assertQueryResultUnordered(
                "SELECT testEnum.TEST, testEnum.TEST2, testEnum.TEST3, array[testEnum.TEST4]",
                singletonList(ImmutableList.of("\"}\"", "", " ", ImmutableList.of(")))\"\""))));

        assertQueryFails("SELECT test.enum.mood.hello", ".*No key 'HELLO' in enum 'test.enum.Mood'");
    }

    @Test
    public void testEnumCasts()
    {
        assertSingleValue("CAST(CAST(1 AS TINYINT) AS test.enum.mood)", 1L);
        assertSingleValue("CAST('The Bahamas' AS test.enum.country)", "The Bahamas");
        assertSingleValue("CAST(row(1, 1) as row(x BIGINT, y test.enum.mood))", ImmutableList.of(1L, 1L));
        assertSingleValue("CAST(test.enum.mood.MELLOW AS BIGINT)", BIG_VALUE);
        assertSingleValue(
                "cast(map(array[test.enum.country.FRANCE], array[array[test.enum.mood.HAPPY]]) as JSON)",
                "{\"France\":[0]}");
        assertSingleValue(
                "map_filter(MAP(ARRAY[test.enum.country.FRANCE, test.enum.country.US], ARRAY[test.enum.mood.HAPPY, test.enum.mood.SAD]), (k,v) -> CAST(v AS BIGINT) > 0)",
                ImmutableMap.of("United States", 1L));
        assertSingleValue(
                "cast(JSON '{\"France\": [0]}' as MAP<test.enum.country,ARRAY<test.enum.mood>>)",
                ImmutableMap.of("France", singletonList(0L)));
        assertQueryFails("select cast(7 as test.enum.mood)", ".*No value '7' in enum 'test.enum.Mood'");
    }

    @Test
    public void testVarcharEnumComparisonOperators()
    {
        assertSingleValue("test.enum.country.US = CAST('United States' AS test.enum.country)", true);
        assertSingleValue("test.enum.country.FRANCE = test.enum.country.BAHAMAS", false);

        assertSingleValue("test.enum.country.FRANCE != test.enum.country.US", true);
        assertSingleValue("array[test.enum.country.FRANCE, test.enum.country.BAHAMAS] != array[test.enum.country.US, test.enum.country.BAHAMAS]", true);

        assertSingleValue("test.enum.country.CHINA IN (test.enum.country.US, null, test.enum.country.BAHAMAS, test.enum.country.China)", true);
        assertSingleValue("test.enum.country.BAHAMAS IN (test.enum.country.US, test.enum.country.FRANCE)", false);

        assertSingleValue("test.enum.country.BAHAMAS < test.enum.country.US", true);
        assertSingleValue("test.enum.country.BAHAMAS < test.enum.country.BAHAMAS", false);

        assertSingleValue("test.enum.country.\"भारत\" <= test.enum.country.\"भारत\"", true);
        assertSingleValue("test.enum.country.\"भारत\" <= test.enum.country.FRANCE", false);

        assertSingleValue("test.enum.country.\"भारत\" >= test.enum.country.FRANCE", true);
        assertSingleValue("test.enum.country.BAHAMAS >= test.enum.country.US", false);

        assertSingleValue("test.enum.country.\"भारत\" > test.enum.country.FRANCE", true);
        assertSingleValue("test.enum.country.CHINA > test.enum.country.CHINA", false);

        assertSingleValue("test.enum.country.\"भारत\" between test.enum.country.FRANCE and test.enum.country.BAHAMAS", true);
        assertSingleValue("test.enum.country.US between test.enum.country.FRANCE and test.enum.country.\"भारत\"", false);

        assertQueryFails("select test.enum.country.US = test.enum.mood.HAPPY", ".* '=' cannot be applied to test.enum.Country.*, test.enum.Mood.*");
        assertQueryFails("select test.enum.country.US IN (test.enum.country.CHINA, test.enum.mood.SAD)", ".* All IN list values must be the same type.*");
        assertQueryFails("select test.enum.country.US IN (test.enum.mood.HAPPY, test.enum.mood.SAD)", ".* IN value and list items must be the same type: test.enum.Country");
        assertQueryFails("select test.enum.country.US > 2", ".* '>' cannot be applied to test.enum.Country.*, integer");
    }

    @Test
    public void testLongEnumComparisonOperators()
    {
        assertSingleValue("test.enum.mood.HAPPY = CAST(0 AS test.enum.mood)", true);
        assertSingleValue("test.enum.mood.HAPPY = test.enum.mood.SAD", false);

        assertSingleValue("test.enum.mood.SAD != test.enum.mood.MELLOW", true);
        assertSingleValue("array[test.enum.mood.HAPPY, test.enum.mood.SAD] != array[test.enum.mood.SAD, test.enum.mood.HAPPY]", true);

        assertSingleValue("test.enum.mood.SAD IN (test.enum.mood.HAPPY, null, test.enum.mood.SAD)", true);
        assertSingleValue("test.enum.mood.HAPPY IN (test.enum.mood.SAD, test.enum.mood.MELLOW)", false);

        assertSingleValue("test.enum.mood.CURIOUS < test.enum.mood.MELLOW", true);
        assertSingleValue("test.enum.mood.SAD < test.enum.mood.HAPPY", false);

        assertSingleValue("test.enum.mood.HAPPY <= test.enum.mood.HAPPY", true);
        assertSingleValue("test.enum.mood.HAPPY <= test.enum.mood.CURIOUS", false);

        assertSingleValue("test.enum.mood.MELLOW >= test.enum.mood.SAD", true);
        assertSingleValue("test.enum.mood.HAPPY >= test.enum.mood.SAD", false);

        assertSingleValue("test.enum.mood.SAD > test.enum.mood.HAPPY", true);
        assertSingleValue("test.enum.mood.HAPPY > test.enum.mood.HAPPY", false);

        assertSingleValue("test.enum.mood.HAPPY between test.enum.mood.CURIOUS and test.enum.mood.SAD ", true);
        assertSingleValue("test.enum.mood.MELLOW between test.enum.mood.SAD and test.enum.mood.HAPPY", false);

        assertQueryFails("select test.enum.mood.HAPPY = 3", ".* '=' cannot be applied to test.enum.Mood.*, integer");
    }

    @Test
    public void testEnumHashOperators()
    {
        assertQueryResultUnordered(
                "SELECT DISTINCT x " +
                        "FROM (VALUES test.enum.mood.happy, test.enum.mood.sad, test.enum.mood.sad, test.enum.mood.happy) t(x)",
                ImmutableList.of(
                        ImmutableList.of(0L),
                        ImmutableList.of(1L)));

        assertQueryResultUnordered(
                "SELECT DISTINCT x " +
                        "FROM (VALUES test.enum.country.FRANCE, test.enum.country.FRANCE, test.enum.country.\"भारत\") t(x)",
                ImmutableList.of(
                        ImmutableList.of("France"),
                        ImmutableList.of("India")));

        assertQueryResultUnordered(
                "SELECT APPROX_DISTINCT(x), APPROX_DISTINCT(y)" +
                        "FROM (VALUES (test.enum.country.FRANCE, test.enum.mood.HAPPY), " +
                        "             (test.enum.country.FRANCE, test.enum.mood.SAD)," +
                        "             (test.enum.country.US, test.enum.mood.HAPPY)) t(x, y)",
                ImmutableList.of(
                        ImmutableList.of(2L, 2L)));
    }

    @Test
    public void testEnumAggregation()
    {
        assertQueryResultUnordered(
                "  SELECT a, ARRAY_AGG(DISTINCT b) " +
                        "FROM (VALUES (test.enum.mood.happy, test.enum.country.us), " +
                        "             (test.enum.mood.happy, test.enum.country.china)," +
                        "             (test.enum.mood.happy, test.enum.country.CHINA)," +
                        "             (test.enum.mood.sad, test.enum.country.us)) t(a, b)" +
                        "GROUP BY a",
                ImmutableList.of(
                        ImmutableList.of(0L, ImmutableList.of("United States", "中国")),
                        ImmutableList.of(1L, ImmutableList.of("United States"))));
    }

    @Test
    public void testEnumJoin()
    {
        assertQueryResultUnordered(
                "  SELECT t1.a, t2.b " +
                        "FROM (VALUES test.enum.mood.happy, test.enum.mood.sad, test.enum.mood.mellow) t1(a) " +
                        "JOIN (VALUES (test.enum.mood.sad, 'hello'), (test.enum.mood.happy, 'world')) t2(a, b) " +
                        "ON t1.a = t2.a",
                ImmutableList.of(
                        ImmutableList.of(1L, "hello"),
                        ImmutableList.of(0L, "world")));
    }

    @Test
    public void testEnumWindow()
    {
        assertQueryResultUnordered(
                "  SELECT first_value(b) OVER (PARTITION BY a ORDER BY a) AS rnk " +
                        "FROM (VALUES (test.enum.mood.happy, 1), (test.enum.mood.happy, 3), (test.enum.mood.sad, 5)) t(a, b)",
                ImmutableList.of(singletonList(1), singletonList(1), singletonList(5)));
    }

    @Test
    public void testCastFunctionCaching()
    {
        assertSingleValue("CAST(' ' as TestEnum)", " ");
        assertSingleValue("CAST(8 as TestLongEnum)", 8L);
    }
}
