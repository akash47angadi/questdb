/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
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

package io.questdb.griffin.engine.join;

import io.questdb.cairo.*;
import io.questdb.cairo.map.FastMap;
import io.questdb.cairo.map.Map;
import io.questdb.cairo.map.MapKey;
import io.questdb.cairo.map.MapValue;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.std.CharSequenceIntHashMap;
import io.questdb.std.Chars;
import io.questdb.std.Misc;
import io.questdb.std.ObjList;
import io.questdb.std.str.CharSink;

import java.io.Closeable;

public class JoinRecordMetadata extends BaseRecordMetadata implements Closeable {

    private final static ColumnTypes keyTypes;
    private final static ColumnTypes valueTypes;

    static {
        final ArrayColumnTypes kt = new ArrayColumnTypes();
        kt.add(ColumnType.STRING);
        kt.add(ColumnType.STRING);
        keyTypes = kt;
        valueTypes = new SingleColumnType(ColumnType.INT);
    }

    private final Map map;
    private int refCount;

    public JoinRecordMetadata(CairoConfiguration configuration, int columnCount) {
        this.map = new FastMap(configuration.getSqlJoinMetadataPageSize(), keyTypes, valueTypes, columnCount * 2, 0.6);
        this.timestampIndex = -1;
        this.columnCount = 0;
        this.columnNameIndexMap = new CharSequenceIntHashMap(columnCount);
        this.columnMetadata = new ObjList<>(columnCount);
        this.refCount = 1;
    }

    public void add(CharSequence tableAlias, CharSequence columnName, int columnType) {
        int dot = Chars.indexOf(columnName, '.');
        // add column with its own alias
        MapKey key = map.withKey();

        if (dot == -1) {
            key.putStr(tableAlias);
        } else {
            assert tableAlias == null;
            key.putStr(columnName, 0, dot);
        }
        key.putStr(columnName, dot + 1, columnName.length());

        MapValue value = key.createValue();
        if (!value.isNew()) {
            throw CairoException.instance(0).put("Duplicate column [name=").put(columnName).put(", tableAlias=").put(tableAlias).put(']');
        }

        value.putLong(0, columnCount++);
        final CharSink b = Misc.getThreadLocalBuilder();
        TableColumnMetadata cm;
        if (dot == -1) {
            cm = new TableColumnMetadata(b.put(tableAlias).put('.').put(columnName).toString(), columnType);
        } else {
            cm = new TableColumnMetadata(Chars.stringOf(columnName), columnType);
        }
        this.columnMetadata.add(cm);

        key = map.withKey();
        key.putStr(null);
        key.putStr(columnName, dot + 1, columnName.length());

        value = key.createValue();
        if (value.isNew()) {
            value.putInt(0, columnCount - 1);
        } else {
            // this is a duplicate columns, if somebody looks it up without alias
            // we would treat this lookup as if column hadn't been found.
            value.putInt(0, -1);
        }
    }

    public void copyColumnMetadataFrom(CharSequence alias, RecordMetadata fromMetadata) {
        for (int i = 0, n = fromMetadata.getColumnCount(); i < n; i++) {
            add(alias, fromMetadata.getColumnName(i), fromMetadata.getColumnType(i));
        }
    }

    @Override
    public void close() {
        if (--refCount < 1) {
            map.close();
        }
    }

    public void incrementRefCount() {
        refCount++;
    }

    @Override
    public int getColumnIndexQuiet(CharSequence columnName) {
        final MapKey key = map.withKey();
        final int dot = Chars.indexOf(columnName, '.');
        if (dot == -1) {
            key.putStr(null);
            key.putStr(columnName);
        } else {
            key.putStr(columnName, 0, dot);
            key.putStr(columnName, dot + 1, columnName.length());
        }

        MapValue value = key.findValue();
        if (value != null) {
            return value.getInt(0);
        }
        return -1;
    }

    public void setTimestampIndex(int index) {
        this.timestampIndex = index;
    }
}