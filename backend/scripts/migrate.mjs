import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import pg from 'pg';

const __dirname = dirname(fileURLToPath(import.meta.url));

async function main() {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    console.log('[migrate] No DATABASE_URL set — skipping migration.');
    process.exit(0);
  }

  const pool = new pg.Pool({ connectionString: databaseUrl });
  try {
    const schemaPath = resolve(__dirname, '..', 'src', 'voicebridge', 'repositories', 'schema.sql');
    const schema = readFileSync(schemaPath, 'utf-8');
    await pool.query(schema);
    console.log('[migrate] Schema applied successfully.');
  } catch (err) {
    console.error('[migrate] Failed:', err.message);
    process.exit(1);
  } finally {
    await pool.end();
  }
}

main();
