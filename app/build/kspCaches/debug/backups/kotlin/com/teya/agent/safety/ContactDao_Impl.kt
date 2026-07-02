package com.teya.agent.safety

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class ContactDao_Impl(
  __db: RoomDatabase,
) : ContactDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfContact: EntityInsertAdapter<Contact>
  init {
    this.__db = __db
    this.__insertAdapterOfContact = object : EntityInsertAdapter<Contact>() {
      protected override fun createQuery(): String = "INSERT OR ABORT INTO `contact_allowlist` (`id`,`name`,`phoneNumber`,`relation`) VALUES (nullif(?, 0),?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: Contact) {
        statement.bindLong(1, entity.id.toLong())
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.phoneNumber)
        val _tmpRelation: String? = entity.relation
        if (_tmpRelation == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpRelation)
        }
      }
    }
  }

  public override suspend fun insert(contact: Contact): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfContact.insert(_connection, contact)
  }

  public override suspend fun getAll(): List<Contact> {
    val _sql: String = "SELECT * FROM contact_allowlist"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfPhoneNumber: Int = getColumnIndexOrThrow(_stmt, "phoneNumber")
        val _columnIndexOfRelation: Int = getColumnIndexOrThrow(_stmt, "relation")
        val _result: MutableList<Contact> = mutableListOf()
        while (_stmt.step()) {
          val _item: Contact
          val _tmpId: Int
          _tmpId = _stmt.getLong(_columnIndexOfId).toInt()
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpPhoneNumber: String
          _tmpPhoneNumber = _stmt.getText(_columnIndexOfPhoneNumber)
          val _tmpRelation: String?
          if (_stmt.isNull(_columnIndexOfRelation)) {
            _tmpRelation = null
          } else {
            _tmpRelation = _stmt.getText(_columnIndexOfRelation)
          }
          _item = Contact(_tmpId,_tmpName,_tmpPhoneNumber,_tmpRelation)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun findByName(name: String): Contact? {
    val _sql: String = "SELECT * FROM contact_allowlist WHERE name LIKE ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, name)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfPhoneNumber: Int = getColumnIndexOrThrow(_stmt, "phoneNumber")
        val _columnIndexOfRelation: Int = getColumnIndexOrThrow(_stmt, "relation")
        val _result: Contact?
        if (_stmt.step()) {
          val _tmpId: Int
          _tmpId = _stmt.getLong(_columnIndexOfId).toInt()
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpPhoneNumber: String
          _tmpPhoneNumber = _stmt.getText(_columnIndexOfPhoneNumber)
          val _tmpRelation: String?
          if (_stmt.isNull(_columnIndexOfRelation)) {
            _tmpRelation = null
          } else {
            _tmpRelation = _stmt.getText(_columnIndexOfRelation)
          }
          _result = Contact(_tmpId,_tmpName,_tmpPhoneNumber,_tmpRelation)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun delete(id: Int) {
    val _sql: String = "DELETE FROM contact_allowlist WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id.toLong())
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
