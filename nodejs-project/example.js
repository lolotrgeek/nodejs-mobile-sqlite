const sqlite = require('./sqlite3')

const db = new sqlite.Database('testDB.db', 'OPEN_READWRITE | OPEN_CREATE', err => {
    console.log('[node] db :' + err ? err : 'Open Create success')
})

const tableName = 'test'

db.run(`CREATE TABLE IF NOT EXISTS ${tableName} (keyField PRIMARY KEY, key, field, val, rel, state, type)`, [], (err) => {
    console.log('[node] db :' + err ? err : 'Create Success!')
})

db.run(
    `INSERT OR REPLACE INTO ${tableName} (keyField, key, field, val, rel, state, type) VALUES (?,?,?, COALESCE(?, ""),COALESCE(?, ""),COALESCE(?, 0),COALESCE(?, 3))`,
    ['keyField', 'node.key', 'node.field', 'node.val', 'node.rel', 'node.state', 'coerce(node.val)'],
    err => console.log('[node] db :' + err ? err : 'Insert Success!')
)

db.all(`SELECT * FROM ${tableName}`, [], (err, rows) => {
    err ? console.log(err) : rows.map(row => console.log('Row: ', row))
})