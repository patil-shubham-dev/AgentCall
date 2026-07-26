import { logger } from '../common/logger.js';
import { publishCallDeleted } from './calls/publisher.js';
import type { VoiceCallSession } from './types.js';

export class DeletionCoordinator {
  handleDeleted(session: VoiceCallSession): void {
    const completedMs = session.completedAt ? new Date(session.completedAt).getTime() : 0;
    const retentionMs = completedMs > 0 ? Date.now() - completedMs : 0;

    logger.info(
      {
        callId: session.id,
        userId: session.userId,
        status: session.status,
        reason: 'retention_expired',
        completedAt: session.completedAt,
        retentionExpiresAt: session.retentionExpiresAt,
        retentionMs,
      },
      '[DeletionCoordinator] session deleted',
    );

    publishCallDeleted(session.userId, session.id, session.status, retentionMs);
  }
}
