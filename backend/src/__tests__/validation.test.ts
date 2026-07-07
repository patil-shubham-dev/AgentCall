import { describe, it, expect } from 'vitest';
import { validate, createCallSchema, cancelCallSchema, deviceRegisterSchema } from '../common/validation.js';
import { ValidationError } from '../common/errors.js';

describe('Validation', () => {
  describe('createCallSchema', () => {
    const validInput = {
      user_id: '550e8400-e29b-41d4-a716-446655440000',
      agent_id: '550e8400-e29b-41d4-a716-446655440001',
      context: {
        reason: 'clarification',
        summary: 'Need help with deployment',
      },
    };

    it('should pass with valid input', () => {
      const result = validate(createCallSchema, validInput);
      expect(result.user_id).toBe(validInput.user_id);
      expect(result.context.reason).toBe('clarification');
      expect(result.priority).toBe('normal');
      expect(result.timeout_seconds).toBe(30);
    });

    it('should accept all valid reasons', () => {
      for (const reason of ['clarification', 'approval', 'error', 'input_required'] as const) {
        const result = validate(createCallSchema, {
          ...validInput,
          context: { ...validInput.context, reason },
        });
        expect(result.context.reason).toBe(reason);
      }
    });

    it('should reject invalid reason', () => {
      expect(() =>
        validate(createCallSchema, {
          ...validInput,
          context: { ...validInput.context, reason: 'invalid_reason' },
        }),
      ).toThrow(ValidationError);
    });

    it('should accept custom priority', () => {
      const result = validate(createCallSchema, {
        ...validInput,
        priority: 'urgent',
      });
      expect(result.priority).toBe('urgent');
    });

    it('should reject missing user_id', () => {
      expect(() =>
        validate(createCallSchema, { ...validInput, user_id: undefined }),
      ).toThrow(ValidationError);
    });

    it('should reject missing context', () => {
      expect(() =>
        validate(createCallSchema, { ...validInput, context: undefined }),
      ).toThrow(ValidationError);
    });

    it('should reject timeout out of range', () => {
      expect(() =>
        validate(createCallSchema, { ...validInput, timeout_seconds: 500 }),
      ).toThrow(ValidationError);

      expect(() =>
        validate(createCallSchema, { ...validInput, timeout_seconds: 5 }),
      ).toThrow(ValidationError);
    });

    it('should accept options array', () => {
      const result = validate(createCallSchema, {
        ...validInput,
        context: {
          ...validInput.context,
          options: ['Option A', 'Option B', 'Option C'],
        },
      });
      expect(result.context.options).toHaveLength(3);
    });

    it('should reject options with more than 10 items', () => {
      expect(() =>
        validate(createCallSchema, {
          ...validInput,
          context: {
            ...validInput.context,
            options: Array.from({ length: 11 }, (_, i) => `Option ${i}`),
          },
        }),
      ).toThrow(ValidationError);
    });
  });

  describe('cancelCallSchema', () => {
    it('should default reason to resolved', () => {
      const result = validate(cancelCallSchema, {});
      expect(result.reason).toBe('resolved');
    });

    it('should accept valid reasons', () => {
      for (const reason of ['resolved', 'timeout', 'error', 'user_requested'] as const) {
        const result = validate(cancelCallSchema, { reason });
        expect(result.reason).toBe(reason);
      }
    });

    it('should reject invalid reason', () => {
      expect(() => validate(cancelCallSchema, { reason: 'unknown' })).toThrow(ValidationError);
    });
  });

  describe('deviceRegisterSchema', () => {
    it('should pass with valid platform', () => {
      for (const platform of ['android', 'ios', 'web'] as const) {
        const result = validate(deviceRegisterSchema, { platform });
        expect(result.platform).toBe(platform);
      }
    });

    it('should reject invalid platform', () => {
      expect(() => validate(deviceRegisterSchema, { platform: 'windows' })).toThrow(ValidationError);
    });

    it('should accept optional push_token', () => {
      const result = validate(deviceRegisterSchema, {
        platform: 'android',
        push_token: 'fcm-token-123',
      });
      expect(result.push_token).toBe('fcm-token-123');
    });

    it('should reject missing platform', () => {
      expect(() => validate(deviceRegisterSchema, {})).toThrow(ValidationError);
    });
  });

  describe('validate helper', () => {
    it('should throw ValidationError for invalid data', () => {
      expect(() => validate(createCallSchema, {})).toThrow(ValidationError);
    });

    it('should include error details in ValidationError', () => {
      try {
        validate(createCallSchema, {});
      } catch (err) {
        expect(err).toBeInstanceOf(ValidationError);
        if (err instanceof ValidationError) {
          expect(err.code).toBe('VALIDATION_ERROR');
          expect(err.details).toHaveProperty('issues');
          expect(Array.isArray(err.details!.issues)).toBe(true);
        }
      }
    });
  });
});
