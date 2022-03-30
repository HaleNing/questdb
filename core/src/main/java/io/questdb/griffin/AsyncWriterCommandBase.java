/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.tasks.TableWriterTask;

abstract class AsyncWriterCommandBase implements AsyncWriterCommand {
    private int cmdType;
    private String cmdName;
    private int tableId;
    private long tableVersion;
    private long correlationId;

    String tableName;
    int tableNamePosition;

    void init(
            int cmdType,
            String cmdName,
            String tableName,
            int tableId,
            long tableVersion,
            int tableNamePosition
    ) {
        this.cmdType = cmdType;
        this.cmdName = cmdName;
        this.tableName = tableName;
        this.tableId = tableId;
        this.tableVersion = tableVersion;
        this.tableNamePosition = tableNamePosition;
    }

    @Override
    public int getTableId() {
        return tableId;
    }

    @Override
    public long getTableVersion() {
        return tableVersion;
    }

    @Override
    public String getTableName() {
        return tableName;
    }

    @Override
    public String getCommandName() {
        return cmdName;
    }

    @Override
    public int getTableNamePosition() {
        return tableNamePosition;
    }

    @Override
    public void setCommandCorrelationId(long correlationId) {
        this.correlationId = correlationId;
    }

    @Override
    public void serialize(TableWriterTask task) {
        task.of(cmdType, tableId, tableName);
        task.setInstance(correlationId);
    }
}
