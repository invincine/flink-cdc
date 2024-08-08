/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.cdc.connectors.mongodb.table;

import org.apache.flink.cdc.connectors.mongodb.source.MongoDBSourceTestBase;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.planner.factories.TestValuesTableFactory;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.apache.flink.cdc.connectors.mongodb.utils.MongoDBContainer.FLINK_USER;
import static org.apache.flink.cdc.connectors.mongodb.utils.MongoDBContainer.FLINK_USER_PASSWORD;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;

/** Integration tests to check mongodb-cdc works well under different local timezone. */
@RunWith(Parameterized.class)
public class MongoDBTimeZoneITCase extends MongoDBSourceTestBase {

    private final StreamExecutionEnvironment env =
            StreamExecutionEnvironment.getExecutionEnvironment();
    private final StreamTableEnvironment tEnv =
            StreamTableEnvironment.create(
                    env, EnvironmentSettings.newInstance().inStreamingMode().build());

    private final String localTimeZone;

    private final boolean parallelismSnapshot;

    public MongoDBTimeZoneITCase(
            String mongoVersion, String localTimeZone, boolean parallelismSnapshot) {
        super(mongoVersion);
        this.localTimeZone = localTimeZone;
        this.parallelismSnapshot = parallelismSnapshot;
    }

    @Parameterized.Parameters(
            name = "mongoVersion: {0}, localTimeZone: {1}, parallelismSnapshot: {2}")
    public static Object[] parameters() {
        List<Object[]> parameterTuples = new ArrayList<>();
        for (String mongoVersion : MONGO_VERSIONS) {
            for (String timezone : new String[] {"Asia/Shanghai", "Europe/Berlin", "UTC"}) {
                for (boolean parallelismSnapshot : new boolean[] {true, false}) {
                    parameterTuples.add(new Object[] {mongoVersion, timezone, parallelismSnapshot});
                }
            }
        }
        return parameterTuples.toArray();
    }

    @Before
    public void before() {
        TestValuesTableFactory.clearAllData();
        if (parallelismSnapshot) {
            env.setParallelism(DEFAULT_PARALLELISM);
            env.enableCheckpointing(200);
        } else {
            env.setParallelism(1);
        }
    }

    @Test
    public void testTemporalTypesWithTimeZone() throws Exception {
        tEnv.getConfig().setLocalTimeZone(ZoneId.of(localTimeZone));

        String database = mongoContainer.executeCommandFileInSeparateDatabase("column_type_test");

        String sourceDDL =
                String.format(
                        "CREATE TABLE full_types (\n"
                                + "    _id STRING,\n"
                                + "    timeField TIME,\n"
                                + "    dateField DATE,\n"
                                + "    dateToTimestampField TIMESTAMP(3),\n"
                                + "    dateToLocalTimestampField TIMESTAMP_LTZ(3),\n"
                                + "    timestampField TIMESTAMP(0),\n"
                                + "    timestampToLocalTimestampField TIMESTAMP_LTZ(0),\n"
                                + "    PRIMARY KEY (_id) NOT ENFORCED"
                                + ") WITH ("
                                + " 'connector' = 'mongodb-cdc',"
                                + " 'hosts' = '%s',"
                                + " 'username' = '%s',"
                                + " 'password' = '%s',"
                                + " 'database' = '%s',"
                                + " 'collection' = '%s'"
                                + ")",
                        mongoContainer.getHostAndPort(),
                        FLINK_USER,
                        FLINK_USER_PASSWORD,
                        database,
                        "full_types");

        tEnv.executeSql(sourceDDL);

        TableResult result =
                tEnv.executeSql(
                        "SELECT dateField,\n"
                                + "timeField,\n"
                                + "dateToTimestampField,\n"
                                + "dateToLocalTimestampField,\n"
                                + "timestampField,\n"
                                + "timestampToLocalTimestampField\n"
                                + "FROM full_types");

        CloseableIterator<Row> iterator = result.collect();
        String[] expectedSnapshot = null;

        switch (localTimeZone) {
            case "Asia/Shanghai":
                expectedSnapshot =
                        new String[] {
                            "+I[2019-08-12, 01:54:14, 2019-08-12T01:54:14.692, 2019-08-11T17:54:14.692Z, 2019-08-12T01:47:44, 2019-08-11T17:47:44Z]"
                        };
                break;
            case "Europe/Berlin":
                expectedSnapshot =
                        new String[] {
                            "+I[2019-08-11, 19:54:14, 2019-08-11T19:54:14.692, 2019-08-11T17:54:14.692Z, 2019-08-11T19:47:44, 2019-08-11T17:47:44Z]"
                        };
                break;
            default:
                expectedSnapshot =
                        new String[] {
                            "+I[2019-08-11, 17:54:14, 2019-08-11T17:54:14.692, 2019-08-11T17:54:14.692Z, 2019-08-11T17:47:44, 2019-08-11T17:47:44Z]"
                        };
                break;
        }

        List<String> actualSnapshot = fetchRows(iterator, expectedSnapshot.length);
        assertThat(actualSnapshot, containsInAnyOrder(expectedSnapshot));

        result.getJobClient().get().cancel().get();
    }

    @Test
    public void testDateAndTimestampToStringWithTimeZone() throws Exception {
        tEnv.getConfig().setLocalTimeZone(ZoneId.of(localTimeZone));

        String database = mongoContainer.executeCommandFileInSeparateDatabase("column_type_test");

        String sourceDDL =
                String.format(
                        "CREATE TABLE full_types_1 (\n"
                                + "    _id STRING,\n"
                                + "    dateToLocalTimestampField STRING,\n"
                                + "    timestampToLocalTimestampField STRING,\n"
                                + "    PRIMARY KEY (_id) NOT ENFORCED"
                                + ") WITH ("
                                + " 'connector' = 'mongodb-cdc',"
                                + " 'hosts' = '%s',"
                                + " 'username' = '%s',"
                                + " 'password' = '%s',"
                                + " 'database' = '%s',"
                                + " 'collection' = '%s'"
                                + ")",
                        mongoContainer.getHostAndPort(),
                        FLINK_USER,
                        FLINK_USER_PASSWORD,
                        database,
                        "full_types");

        tEnv.executeSql(sourceDDL);

        TableResult result =
                tEnv.executeSql(
                        "SELECT dateToLocalTimestampField,\n"
                                + "timestampToLocalTimestampField\n"
                                + "FROM full_types_1");

        CloseableIterator<Row> iterator = result.collect();
        String[] expectedSnapshot;

        switch (localTimeZone) {
            case "Asia/Shanghai":
                expectedSnapshot =
                        new String[] {
                            "+I[2019-08-12T01:54:14.692+08:00, 2019-08-12T01:47:44+08:00]"
                        };
                break;
            case "Europe/Berlin":
                expectedSnapshot =
                        new String[] {
                            "+I[2019-08-11T19:54:14.692+02:00, 2019-08-11T19:47:44+02:00]"
                        };
                break;
            default:
                expectedSnapshot =
                        new String[] {"+I[2019-08-11T17:54:14.692Z, 2019-08-11T17:47:44Z]"};
                break;
        }

        List<String> actualSnapshot = fetchRows(iterator, expectedSnapshot.length);
        assertThat(actualSnapshot, containsInAnyOrder(expectedSnapshot));

        result.getJobClient().get().cancel().get();
    }

    private static List<String> fetchRows(Iterator<Row> iter, int size) {
        List<String> rows = new ArrayList<>(size);
        while (size > 0 && iter.hasNext()) {
            Row row = iter.next();
            rows.add(row.toString());
            size--;
        }
        return rows;
    }
}