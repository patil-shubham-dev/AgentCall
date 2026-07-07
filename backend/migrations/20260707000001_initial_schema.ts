import type { Knex } from 'knex';

export async function up(knex: Knex): Promise<void> {
  await knex.raw(`CREATE EXTENSION IF NOT EXISTS "pgcrypto"`);

  await knex.schema.createTable('users', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.string('email', 255).unique().notNullable();
    t.string('display_name', 100).notNullable();
    t.text('avatar_url');
    t.string('timezone', 50).defaultTo('UTC');
    t.boolean('do_not_disturb').defaultTo(false);
    t.jsonb('dnd_schedule');
    t.jsonb('preferences').defaultTo('{}');
    t.boolean('is_active').defaultTo(true);
    t.timestamps(true, true);
  });

  await knex.schema.createTable('oauth_accounts', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.uuid('user_id').notNullable().references('id').inTable('users').onDelete('CASCADE');
    t.string('provider', 50).notNullable();
    t.string('provider_user_id', 255).notNullable();
    t.string('provider_email', 255);
    t.text('access_token');
    t.text('refresh_token');
    t.timestamp('expires_at');
    t.timestamps(true, true);
    t.unique(['provider', 'provider_user_id']);
  });

  await knex.schema.createTable('devices', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.uuid('user_id').notNullable().references('id').inTable('users').onDelete('CASCADE');
    t.string('platform', 20).notNullable().checkIn(['android', 'ios', 'web']);
    t.text('push_token');
    t.timestamp('push_token_updated_at');
    t.string('device_name', 255);
    t.string('device_model', 100);
    t.string('os_version', 50);
    t.string('app_version', 20);
    t.boolean('is_active').defaultTo(true);
    t.timestamp('last_seen_at');
    t.timestamps(true, true);
  });

  await knex.raw(`
    CREATE TYPE call_status AS ENUM (
      'requested', 'ringing', 'connecting', 'connected',
      'ended', 'cancelled', 'timed_out', 'failed'
    )
  `);
  await knex.raw(`CREATE TYPE call_priority AS ENUM ('low', 'normal', 'high', 'urgent')`);
  await knex.raw(`CREATE TYPE call_reason AS ENUM ('clarification', 'approval', 'error', 'input_required')`);

  await knex.schema.createTable('call_sessions', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.uuid('user_id').notNullable().references('id').inTable('users').onDelete('RESTRICT');
    t.uuid('agent_id').notNullable().references('id').inTable('users').onDelete('RESTRICT');
    t.specificType('status', 'call_status').notNullable().defaultTo('requested');
    t.specificType('priority', 'call_priority').notNullable().defaultTo('normal');
    t.specificType('reason', 'call_reason').notNullable();
    t.jsonb('context').notNullable().defaultTo('{}');
    t.jsonb('result');
    t.integer('timeout_seconds').notNullable().defaultTo(30);
    t.timestamp('expires_at');
    t.timestamp('requested_at').defaultTo(knex.fn.now());
    t.timestamp('ringing_at');
    t.timestamp('connected_at');
    t.timestamp('ended_at');
    t.integer('duration_ms');
    t.timestamps(true, true);
  });

  await knex.schema.createTable('call_quality_metrics', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.uuid('call_id').notNullable().references('id').inTable('call_sessions').onDelete('CASCADE');
    t.float('avg_jitter_ms');
    t.float('max_jitter_ms');
    t.float('avg_rtt_ms');
    t.float('max_rtt_ms');
    t.float('packet_loss_pct');
    t.float('bitrate_kbps');
    t.string('codec', 20).defaultTo('opus');
    t.integer('sample_rate').defaultTo(48000);
    t.string('ice_connection_type', 20);
    t.boolean('turn_used').defaultTo(false);
    t.timestamp('created_at').defaultTo(knex.fn.now());
  });

  await knex.raw(`CREATE TYPE participant_role AS ENUM ('caller', 'callee', 'observer')`);

  await knex.schema.createTable('call_participants', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.uuid('call_id').notNullable().references('id').inTable('call_sessions').onDelete('CASCADE');
    t.uuid('user_id').notNullable().references('id').inTable('users').onDelete('CASCADE');
    t.specificType('role', 'participant_role').notNullable();
    t.timestamp('joined_at');
    t.timestamp('left_at');
    t.boolean('muted').defaultTo(false);
    t.float('audio_level');
    t.timestamps(true, true);
    t.unique(['call_id', 'user_id']);
  });

  await knex.raw(`CREATE TYPE notification_status AS ENUM ('queued', 'delivered', 'failed', 'expired')`);

  await knex.schema.createTable('notification_log', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.uuid('user_id').notNullable().references('id').inTable('users').onDelete('CASCADE');
    t.uuid('device_id').references('id').inTable('devices').onDelete('SET NULL');
    t.uuid('call_id').references('id').inTable('call_sessions').onDelete('SET NULL');
    t.string('notification_type', 50).notNullable();
    t.specificType('status', 'notification_status').notNullable().defaultTo('queued');
    t.string('provider', 10);
    t.string('provider_message_id');
    t.text('error_message');
    t.timestamp('delivered_at');
    t.timestamp('created_at').defaultTo(knex.fn.now());
  });

  await knex.schema.createTable('auth_refresh_tokens', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.uuid('user_id').notNullable().references('id').inTable('users').onDelete('CASCADE');
    t.string('token_hash', 64).unique().notNullable();
    t.uuid('device_id').references('id').inTable('devices').onDelete('SET NULL');
    t.timestamp('expires_at').notNullable();
    t.boolean('revoked').defaultTo(false);
    t.timestamp('created_at').defaultTo(knex.fn.now());
  });

  await knex.schema.createTable('token_blacklist', (t) => {
    t.string('jti', 64).primary();
    t.timestamp('expires_at').notNullable();
    t.timestamp('created_at').defaultTo(knex.fn.now());
  });

  await knex.schema.createTable('api_keys', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.uuid('user_id').notNullable().references('id').inTable('users').onDelete('CASCADE');
    t.string('name', 100).notNullable();
    t.string('key_prefix', 8).notNullable();
    t.string('key_hash', 64).unique().notNullable();
    t.jsonb('permissions').defaultTo('["create_call", "query_presence"]');
    t.timestamp('last_used_at');
    t.timestamp('expires_at');
    t.boolean('is_active').defaultTo(true);
    t.timestamps(true, true);
  });

  await knex.schema.createTable('audit_log', (t) => {
    t.uuid('id').primary().defaultTo(knex.raw('gen_random_uuid()'));
    t.string('event_type', 50).notNullable();
    t.uuid('actor_id');
    t.uuid('target_id');
    t.specificType('ip_address', 'inet');
    t.text('user_agent');
    t.jsonb('metadata');
    t.string('severity', 20);
    t.timestamp('created_at').defaultTo(knex.fn.now());
  });

  // Indexes
  await knex.raw(`CREATE INDEX idx_users_active ON users(is_active) WHERE is_active = true`);
  await knex.raw(`CREATE INDEX idx_oauth_user ON oauth_accounts(user_id)`);
  await knex.raw(`CREATE INDEX idx_devices_user ON devices(user_id, is_active) WHERE is_active = true`);
  await knex.raw(`CREATE INDEX idx_devices_push_token ON devices(push_token) WHERE push_token IS NOT NULL`);
  await knex.raw(`CREATE INDEX idx_calls_user_status ON call_sessions(user_id, status)`);
  await knex.raw(`CREATE INDEX idx_calls_agent_status ON call_sessions(agent_id, status)`);
  await knex.raw(`CREATE INDEX idx_calls_active ON call_sessions(status, created_at) WHERE status IN ('requested', 'ringing', 'connected')`);
  await knex.raw(`CREATE INDEX idx_calls_expires ON call_sessions(expires_at) WHERE expires_at IS NOT NULL`);
  await knex.raw(`CREATE INDEX idx_quality_call ON call_quality_metrics(call_id)`);
  await knex.raw(`CREATE INDEX idx_participants_call ON call_participants(call_id)`);
  await knex.raw(`CREATE INDEX idx_notif_user ON notification_log(user_id, created_at DESC)`);
  await knex.raw(`CREATE INDEX idx_notif_queued ON notification_log(status) WHERE status = 'queued'`);
  await knex.raw(`CREATE INDEX idx_refresh_user ON auth_refresh_tokens(user_id)`);
  await knex.raw(`CREATE INDEX idx_apikeys_user ON api_keys(user_id)`);
  await knex.raw(`CREATE INDEX idx_audit_actor ON audit_log(actor_id, created_at DESC)`);
  await knex.raw(`CREATE INDEX idx_audit_severity ON audit_log(severity) WHERE severity IN ('warning', 'critical')`);
}

export async function down(knex: Knex): Promise<void> {
  await knex.schema.dropTableIfExists('audit_log');
  await knex.schema.dropTableIfExists('api_keys');
  await knex.schema.dropTableIfExists('token_blacklist');
  await knex.schema.dropTableIfExists('auth_refresh_tokens');
  await knex.schema.dropTableIfExists('notification_log');
  await knex.schema.dropTableIfExists('call_participants');
  await knex.schema.dropTableIfExists('call_quality_metrics');
  await knex.schema.dropTableIfExists('call_sessions');
  await knex.schema.dropTableIfExists('devices');
  await knex.schema.dropTableIfExists('oauth_accounts');
  await knex.schema.dropTableIfExists('users');

  await knex.raw('DROP TYPE IF EXISTS participant_role');
  await knex.raw('DROP TYPE IF EXISTS notification_status');
  await knex.raw('DROP TYPE IF EXISTS call_reason');
  await knex.raw('DROP TYPE IF EXISTS call_priority');
  await knex.raw('DROP TYPE IF EXISTS call_status');
}
