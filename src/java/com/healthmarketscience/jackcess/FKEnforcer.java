/*
Copyright (c) 2012 James Ahlborn

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
*/

package com.healthmarketscience.jackcess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


/**
 * Utility class used by Table to enforce foreign-key relationships (if
 * enabled).
 *
 * @author James Ahlborn
 * @usage _advanced_class_
 */
final class FKEnforcer 
{
  // fk constraints always work with indexes, which are always
  // case-insensitive
  private static final ColumnMatcher MATCHER =
    CaseInsensitiveColumnMatcher.INSTANCE;

  private final TableImpl _table;
  private final List<Column> _cols;
  private List<Joiner> _primaryJoinersChkUp;
  private List<Joiner> _primaryJoinersChkDel;
  private List<Joiner> _primaryJoinersDoUp;
  private List<Joiner> _primaryJoinersDoDel;
  private List<Joiner> _secondaryJoiners;

  FKEnforcer(TableImpl table) {
    _table = table;

    // at this point, only init the index columns
    Set<Column> cols = new TreeSet<Column>();
    for(IndexImpl idx : _table.getIndexes()) {
      IndexImpl.ForeignKeyReference ref = idx.getReference();
      if(ref != null) {
        // compile an ordered list of all columns in this table which are
        // involved in foreign key relationships with other tables
        for(IndexData.ColumnDescriptor iCol : idx.getColumns()) {
          cols.add(iCol.getColumn());
        }
      }
    }
    _cols = !cols.isEmpty() ?
      Collections.unmodifiableList(new ArrayList<Column>(cols)) :
      Collections.<Column>emptyList();
  }

  /**
   * Does secondary initialization, if necessary.
   */
  private void initialize() throws IOException {
    if(_secondaryJoiners != null) {
      // already initialized
      return;
    }

    // initialize all the joiners
    _primaryJoinersChkUp = new ArrayList<Joiner>(1);
    _primaryJoinersChkDel = new ArrayList<Joiner>(1);
    _primaryJoinersDoUp = new ArrayList<Joiner>(1);
    _primaryJoinersDoDel = new ArrayList<Joiner>(1);
    _secondaryJoiners = new ArrayList<Joiner>(1);

    for(IndexImpl idx : _table.getIndexes()) {
      IndexImpl.ForeignKeyReference ref = idx.getReference();
      if(ref != null) {

        Joiner joiner = Joiner.create(idx);
        if(ref.isPrimaryTable()) {
          if(ref.isCascadeUpdates()) {
            _primaryJoinersDoUp.add(joiner);
          } else {
            _primaryJoinersChkUp.add(joiner);
          }
          if(ref.isCascadeDeletes()) {
            _primaryJoinersDoDel.add(joiner);
          } else {
            _primaryJoinersChkDel.add(joiner);
          }
        } else {
          _secondaryJoiners.add(joiner);
        }
      }
    }
  }

  /**
   * Handles foregn-key constraints when adding a row.
   *
   * @param row new row in the Table's row format, including all values used
   *            in any foreign-key relationships
   */
  public void addRow(Object[] row) throws IOException {
    if(!enforcing()) {
      return;
    }
    initialize();

    for(Joiner joiner : _secondaryJoiners) {
      requirePrimaryValues(joiner, row);
    }
  }

  /**
   * Handles foregn-key constraints when updating a row.
   *
   * @param oldRow old row in the Table's row format, including all values
   *               used in any foreign-key relationships
   * @param newRow new row in the Table's row format, including all values
   *               used in any foreign-key relationships
   */
  public void updateRow(Object[] oldRow, Object[] newRow) throws IOException {
    if(!enforcing()) {
      return;
    }

    if(!anyUpdates(oldRow, newRow)) {
      // no changes were made to any relevant columns
      return;
    }

    initialize();

    SharedState ss = _table.getDatabase().getFKEnforcerSharedState();
    
    if(ss.isUpdating()) {
      // we only check the primary relationships for the "top-level" of an
      // update operation.  in nested levels we are only ever changing the fk
      // values themselves, so we always know the new values are valid.
      for(Joiner joiner : _secondaryJoiners) {
        if(anyUpdates(joiner, oldRow, newRow)) {
          requirePrimaryValues(joiner, newRow);
        }
      }
    }

    ss.pushUpdate();
    try {

      // now, check the tables for which we are the primary table in the
      // relationship (but not cascading)
      for(Joiner joiner : _primaryJoinersChkUp) {
        if(anyUpdates(joiner, oldRow, newRow)) {
          requireNoSecondaryValues(joiner, oldRow);
        }
      }

      // lastly, update the tables for which we are the primary table in the
      // relationship
      for(Joiner joiner : _primaryJoinersDoUp) {
        if(anyUpdates(joiner, oldRow, newRow)) {
          updateSecondaryValues(joiner, oldRow, newRow);
        }
      }

    } finally {
      ss.popUpdate();
    }
  }

  /**
   * Handles foregn-key constraints when deleting a row.
   *
   * @param row old row in the Table's row format, including all values used
   *            in any foreign-key relationships
   */
  public void deleteRow(Object[] row) throws IOException {
    if(!enforcing()) {
      return;
    }
    initialize();

    // first, check the tables for which we are the primary table in the
    // relationship (but not cascading)
    for(Joiner joiner : _primaryJoinersChkDel) {
      requireNoSecondaryValues(joiner, row);
    }

    // lastly, delete from the tables for which we are the primary table in
    // the relationship
    for(Joiner joiner : _primaryJoinersDoDel) {
      joiner.deleteRows(row);
    }
  }

  private static void requirePrimaryValues(Joiner joiner, Object[] row) 
    throws IOException 
  {
    // ensure that the relevant rows exist in the primary tables for which
    // this table is a secondary table.
    if(!joiner.hasRows(row)) {
      throw new IOException("Adding new row " + Arrays.asList(row) + 
                            " violates constraint " + joiner.toFKString());
    }
  }

  private static void requireNoSecondaryValues(Joiner joiner, Object[] row) 
    throws IOException 
  {
    // ensure that no rows exist in the secondary table for which this table is
    // the primary table.
    if(joiner.hasRows(row)) {
      throw new IOException("Removing old row " + Arrays.asList(row) + 
                            " violates constraint " + joiner.toFKString());
    }
  }

  private static void updateSecondaryValues(Joiner joiner, Object[] oldFromRow,
                                            Object[] newFromRow)
    throws IOException
  {
    IndexCursor toCursor = joiner.getToCursor();
    List<IndexData.ColumnDescriptor> fromCols = joiner.getColumns();
    List<IndexData.ColumnDescriptor> toCols = joiner.getToIndex().getColumns();
    Object[] toRow = new Object[joiner.getToTable().getColumnCount()];

    for(Iterator<Map<String,Object>> iter = joiner.findRows(
            oldFromRow, Collections.<String>emptySet()); iter.hasNext(); ) {
      iter.next();

      // create update row for "to" table
      Arrays.fill(toRow, Column.KEEP_VALUE);
      for(int i = 0; i < fromCols.size(); ++i) {
        Object val = fromCols.get(i).getColumn().getRowValue(newFromRow);
        toCols.get(i).getColumn().setRowValue(toRow, val);
      }

      toCursor.updateCurrentRow(toRow);
    }
  }

  private boolean anyUpdates(Object[] oldRow, Object[] newRow) {
    for(Column col : _cols) {
      if(!MATCHER.matches(_table, col.getName(),
                          col.getRowValue(oldRow), col.getRowValue(newRow))) {
        return true;
      }
    }
    return false;
  }

  private static boolean anyUpdates(Joiner joiner,Object[] oldRow, 
                                    Object[] newRow)
  {
    Table fromTable = joiner.getFromTable();
    for(IndexData.ColumnDescriptor iCol : joiner.getColumns()) {
      Column col = iCol.getColumn();
      if(!MATCHER.matches(fromTable, col.getName(),
                          col.getRowValue(oldRow), col.getRowValue(newRow))) {
        return true;
      }
    }
    return false;
  }

  private boolean enforcing() {
    return _table.getDatabase().isEnforceForeignKeys();
  }

  static SharedState initSharedState() {
    return new SharedState();
  }

  /**
   * Shared state used by all FKEnforcers for a given Database.
   */
  static final class SharedState 
  {
    /** current depth of cascading update calls across one or more tables */
    private int _updateDepth;

    private SharedState() {
    }

    public boolean isUpdating() {
      return (_updateDepth == 0);
    }
    
    public void pushUpdate() {
      ++_updateDepth;
    }

    public void popUpdate() {
      --_updateDepth;
    }
  }
}