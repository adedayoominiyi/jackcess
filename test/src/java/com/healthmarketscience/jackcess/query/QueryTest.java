/*
Copyright (c) 2007 Health Market Science, Inc.

This library is free software; you can redistribute it and/or
modify it under the terms of the GNU Lesser General Public
License as published by the Free Software Foundation; either
version 2.1 of the License, or (at your option) any later version.

This library is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public
License along with this library; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307
USA

You can contact Health Market Science at info@healthmarketscience.com
or at the following address:

Health Market Science
2700 Horizon Drive
Suite 200
King of Prussia, PA 19406
*/

package com.healthmarketscience.jackcess.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import com.healthmarketscience.jackcess.DataType;
import com.healthmarketscience.jackcess.query.Query.Row;
import junit.framework.TestCase;
import org.apache.commons.lang.StringUtils;

import static org.apache.commons.lang.SystemUtils.LINE_SEPARATOR;
import static com.healthmarketscience.jackcess.query.QueryFormat.*;


/**
 * @author James Ahlborn
 */
public class QueryTest extends TestCase
{

  public QueryTest(String name) throws Exception {
    super(name);
  }

  public void testUnionQuery() throws Exception
  {
    String expr1 = "Select * from Table1";
    String expr2 = "Select * from Table2";

    UnionQuery query = (UnionQuery)newQuery(
        Query.Type.UNION, 
        newRow(TABLE_ATTRIBUTE, expr1, null, UNION_PART1),
        newRow(TABLE_ATTRIBUTE, expr2, null, UNION_PART2));
    setFlag(query, 3);

    assertEquals(multiline("Select * from Table1",
                           "UNION Select * from Table2;"), 
                 query.toSQLString());

    setFlag(query, 1);

    assertEquals(multiline("Select * from Table1",
                           "UNION ALL Select * from Table2;"), 
                 query.toSQLString());

    addRows(query, newRow(ORDERBY_ATTRIBUTE, "Table1.id", 
                               null, null));

    assertEquals(multiline("Select * from Table1",
                           "UNION ALL Select * from Table2",
                           "ORDER BY Table1.id;"),
                 query.toSQLString());

    removeRows(query, TABLE_ATTRIBUTE);

    try {
      query.toSQLString();
      fail("IllegalStateException should have been thrown");
    } catch(IllegalStateException e) {
      // success
    }

  }

  public void testPassthroughQuery() throws Exception
  {
    String expr = "Select * from Table1";
    String constr = "ODBC;";

    PassthroughQuery query = (PassthroughQuery)newQuery(
        Query.Type.PASSTHROUGH, expr, constr);

    assertEquals(expr, query.toSQLString());
    assertEquals(constr, query.getConnectionString());
  }

  public void testDataDefinitionQuery() throws Exception
  {
    String expr = "Drop table Table1";

    DataDefinitionQuery query = (DataDefinitionQuery)newQuery(
        Query.Type.DATA_DEFINITION, expr, null);

    assertEquals(expr, query.toSQLString());
  }

  public void testUpdateQuery() throws Exception
  {
    UpdateQuery query = (UpdateQuery)newQuery(
        Query.Type.UPDATE, 
        newRow(TABLE_ATTRIBUTE, null, "Table1", null),
        newRow(COLUMN_ATTRIBUTE, "\"some string\"", null, "Table1.id"),
        newRow(COLUMN_ATTRIBUTE, "42", null, "Table1.col1"));

    assertEquals(
        multiline("UPDATE Table1",
                  "SET Table1.id = \"some string\", Table1.col1 = 42;"),
        query.toSQLString());

    addRows(query, newRow(WHERE_ATTRIBUTE, "(Table1.col2 < 13)", 
                               null, null));

    assertEquals(
        multiline("UPDATE Table1",
                  "SET Table1.id = \"some string\", Table1.col1 = 42",
                  "WHERE (Table1.col2 < 13);"),
        query.toSQLString());
  }

  public void testSelectQuery() throws Exception
  {
    SelectQuery query = (SelectQuery)newQuery(
        Query.Type.SELECT, 
        newRow(TABLE_ATTRIBUTE, null, "Table1", null));
    setFlag(query, 1);

    assertEquals(multiline("SELECT *",
                           "FROM Table1;"),
                 query.toSQLString());
    
    doTestColumns(query);
    doTestSelectFlags(query);
    doTestParameters(query);
    doTestTables(query);
    doTestRemoteDb(query);
    doTestJoins(query);
    doTestWhereExpression(query);
    doTestGroupings(query);
    doTestHavingExpression(query);
    doTestOrderings(query);
  }

  public void testBadQueries() throws Exception
  {
    List<Row> rowList = new ArrayList<Row>();
    rowList.add(newRow(TYPE_ATTRIBUTE, null, -1, null, null));
    Query query = Query.create(-1, "TestQuery", rowList, 13);
    try {
      query.toSQLString();
      fail("UnsupportedOperationException should have been thrown");
    } catch(UnsupportedOperationException e) {
      // success
    }

    addRows(query, newRow(TYPE_ATTRIBUTE, null, -1, null, null));
    
    try {
      query.getTypeRow();
      fail("IllegalStateException should have been thrown");
    } catch(IllegalStateException e) {
      // success
    }

    try {
      new Query("TestQuery", rowList, 13, Query.Type.UNION) {
        @Override protected void toSQLString(StringBuilder builder) {
          throw new UnsupportedOperationException();
        }};
      fail("IllegalStateException should have been thrown");
    } catch(IllegalStateException e) {
      // success
    }

  }

  private void doTestColumns(SelectQuery query) throws Exception
  {
    addRows(query, newRow(COLUMN_ATTRIBUTE, "Table1.id", null, null));
    addRows(query, newRow(COLUMN_ATTRIBUTE, "Table1.col", "Some.Alias", null));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias], *",
                           "FROM Table1;"),
                 query.toSQLString());
  }

  private void doTestSelectFlags(SelectQuery query) throws Exception
  {
    setFlag(query, 3);
    
    assertEquals(multiline("SELECT DISTINCT Table1.id, Table1.col AS [Some.Alias], *",
                           "FROM Table1;"),
                 query.toSQLString());

    setFlag(query, 9);
    
    assertEquals(multiline("SELECT DISTINCTROW Table1.id, Table1.col AS [Some.Alias], *",
                           "FROM Table1;"),
                 query.toSQLString());

    setFlag(query, 7);
    
    assertEquals(multiline("SELECT DISTINCT Table1.id, Table1.col AS [Some.Alias], *",
                           "FROM Table1",
                           "WITH OWNERACCESS OPTION;"),
                 query.toSQLString());

    replaceRows(query, 
                newRow(FLAG_ATTRIBUTE, null, 49, null, "5", null));
    
    assertEquals(multiline("SELECT TOP 5 PERCENT Table1.id, Table1.col AS [Some.Alias], *",
                           "FROM Table1;"),
                 query.toSQLString());

    setFlag(query, 0);
  }

  private void doTestParameters(SelectQuery query) throws Exception
  {
    addRows(query, newRow(PARAMETER_ATTRIBUTE, null, DataType.INT.getValue(), "INT_VAL", null));

    assertEquals(multiline("PARAMETERS INT_VAL Short;",
                           "SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1;"),
                 query.toSQLString());

    addRows(query, newRow(PARAMETER_ATTRIBUTE, null, DataType.TEXT.getValue(), 50, "TextVal", null),
            newRow(PARAMETER_ATTRIBUTE, null, 0, 50, "[Some Value]", null));

    assertEquals(multiline("PARAMETERS INT_VAL Short, TextVal Text(50), [Some Value] Value;",
                           "SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1;"),
                 query.toSQLString());

    addRows(query, newRow(PARAMETER_ATTRIBUTE, null, -1, "BadVal", null));
    try {
      query.toSQLString();
      fail("IllegalStateException should have been thrown");
    } catch(IllegalStateException e) {
      // success
    }

    removeRows(query, PARAMETER_ATTRIBUTE);
  }

  private void doTestTables(SelectQuery query) throws Exception
  {
    addRows(query, newRow(TABLE_ATTRIBUTE, null, "Table2", "Another Table"));
    addRows(query, newRow(TABLE_ATTRIBUTE, "Select val from Table3", "val", "Table3Val"));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1, Table2 AS [Another Table], [Select val from Table3].val AS Table3Val;"),
                 query.toSQLString());    
  }

  private void doTestRemoteDb(SelectQuery query) throws Exception
  {
    addRows(query, newRow(REMOTEDB_ATTRIBUTE, null, 2, "other_db.mdb", null));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1, Table2 AS [Another Table], [Select val from Table3].val AS Table3Val IN 'other_db.mdb';"),
                 query.toSQLString());

    replaceRows(query, newRow(REMOTEDB_ATTRIBUTE, "MDB_FILE;", 2, "other_db.mdb", null));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1, Table2 AS [Another Table], [Select val from Table3].val AS Table3Val IN 'other_db.mdb' [MDB_FILE;];"),
                 query.toSQLString());

    replaceRows(query, newRow(REMOTEDB_ATTRIBUTE, "MDB_FILE;", 2, null, null));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1, Table2 AS [Another Table], [Select val from Table3].val AS Table3Val IN '' [MDB_FILE;];"),
                 query.toSQLString());

    removeRows(query, REMOTEDB_ATTRIBUTE);
  }

  private void doTestJoins(SelectQuery query) throws Exception
  {
    addRows(query, newRow(JOIN_ATTRIBUTE, "(Table1.id = [Another Table].id)", 1, "Table1", "Another Table"));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM [Select val from Table3].val AS Table3Val, Table1 INNER JOIN Table2 AS [Another Table] ON (Table1.id = [Another Table].id);"),
                 query.toSQLString());
    
    addRows(query, newRow(JOIN_ATTRIBUTE, "(Table1.id = Table3Val.id)", 2, "Table1", "Table3Val"));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM (Table1 INNER JOIN Table2 AS [Another Table] ON (Table1.id = [Another Table].id)) LEFT OUTER JOIN [Select val from Table3].val AS Table3Val ON (Table1.id = Table3Val.id);"),
                 query.toSQLString());

    addRows(query, newRow(JOIN_ATTRIBUTE, "(Table1.id = Table3Val.id)", 5, "Table1", "Table3Val"));

    try {
      query.toSQLString();
      fail("IllegalStateException should have been thrown");
    } catch(IllegalStateException e) {
      // success
    }

    removeLastRows(query, 1);
    query.toSQLString();

    addRows(query, newRow(JOIN_ATTRIBUTE, "(Table1.id = Table3Val.id)", 1, "BogusTable", "Table3Val"));

    try {
      query.toSQLString();
      fail("IllegalStateException should have been thrown");
    } catch(IllegalStateException e) {
      // success
    }

    removeRows(query, JOIN_ATTRIBUTE);
  }

  private void doTestWhereExpression(SelectQuery query) throws Exception
  {
    addRows(query, newRow(WHERE_ATTRIBUTE, "(Table1.col2 < 13)", 
                          null, null));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1, Table2 AS [Another Table], [Select val from Table3].val AS Table3Val",
                           "WHERE (Table1.col2 < 13);"),
                 query.toSQLString());    
  }

  private void doTestGroupings(SelectQuery query) throws Exception
  {
    addRows(query, newRow(GROUPBY_ATTRIBUTE, "Table1.id", null, null),
            newRow(GROUPBY_ATTRIBUTE, "SUM(Table1.val)", null, null));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1, Table2 AS [Another Table], [Select val from Table3].val AS Table3Val",
                           "WHERE (Table1.col2 < 13)",
                           "GROUP BY Table1.id, SUM(Table1.val);"),
                 query.toSQLString());    
  }

  private void doTestHavingExpression(SelectQuery query) throws Exception
  {
    addRows(query, newRow(HAVING_ATTRIBUTE, "(SUM(Table1.val) = 500)", null, null));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1, Table2 AS [Another Table], [Select val from Table3].val AS Table3Val",
                           "WHERE (Table1.col2 < 13)",
                           "GROUP BY Table1.id, SUM(Table1.val)",
                           "HAVING (SUM(Table1.val) = 500);"),
                 query.toSQLString());    
  }

  private void doTestOrderings(SelectQuery query) throws Exception
  {
    addRows(query, newRow(ORDERBY_ATTRIBUTE, "Table1.id", null, null),
            newRow(ORDERBY_ATTRIBUTE, "Table2.val", "D", null));

    assertEquals(multiline("SELECT Table1.id, Table1.col AS [Some.Alias]",
                           "FROM Table1, Table2 AS [Another Table], [Select val from Table3].val AS Table3Val",
                           "WHERE (Table1.col2 < 13)",
                           "GROUP BY Table1.id, SUM(Table1.val)",
                           "HAVING (SUM(Table1.val) = 500)",
                           "ORDER BY Table1.id, Table2.val DESC;"),
                 query.toSQLString());    
  }


  private static Query newQuery(Query.Type type, Row... rows)
  {
    return newQuery(type, null, null, rows);
  }

  private static Query newQuery(Query.Type type, String typeExpr,
                                String typeName1, Row... rows)
  {
    List<Row> rowList = new ArrayList<Row>();
    rowList.add(newRow(TYPE_ATTRIBUTE, typeExpr, type.getValue(),
                       null, typeName1, null));
    rowList.addAll(Arrays.asList(rows));
    return Query.create(type.getObjectFlag(), "TestQuery", rowList, 13);
  }

  private static Row newRow(Byte attr, String expr, String name1, String name2)
  {
    return newRow(attr, expr, null, null, name1, name2);
  }

  private static Row newRow(Byte attr, String expr, Number flagNum,
                            String name1, String name2)
  {
    return newRow(attr, expr, flagNum, null, name1, name2);
  }

  private static Row newRow(Byte attr, String expr, Number flagNum,
                            Number extraNum, String name1, String name2)
  {
    Short flag = ((flagNum != null) ? flagNum.shortValue() : null);
    Integer extra = ((extraNum != null) ? extraNum.intValue() : null);
    return new Row(attr, expr, flag, extra, name1, name2, null, null);
  }

  private static void setFlag(Query query, Number newFlagNum)
  {
    replaceRows(query, 
                newRow(FLAG_ATTRIBUTE, null, newFlagNum, null, null, null));
  }

  private static void addRows(Query query, Row... rows)
  {
    query.getRows().addAll(Arrays.asList(rows));
  }

  private static void replaceRows(Query query, Row... rows)
  {
    removeRows(query, rows[0].attribute);
    addRows(query, rows);
  }

  private static void removeRows(Query query, Byte attr)
  {
    for(Iterator<Row> iter = query.getRows().iterator(); iter.hasNext(); ) {
      if(attr.equals(iter.next().attribute)) {
        iter.remove();
      }
    }
  }

  private static void removeLastRows(Query query, int num)
  {
    List<Row> rows = query.getRows();
    int size = rows.size();
    rows.subList(size - num, size).clear();
  }

  private static String multiline(String... strs)
  {
    return StringUtils.join(strs, LINE_SEPARATOR);
  }

}