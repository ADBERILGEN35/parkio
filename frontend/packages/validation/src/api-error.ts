import { z } from 'zod';
import type { ApiError, FieldError } from '@parkio/types';

export const fieldErrorSchema = z
  .object({
    field: z.string(),
    message: z.string(),
  })
  .strip() satisfies z.ZodType<FieldError>;

export const apiErrorSchema = z
  .object({
    code: z.string(),
    message: z.string(),
    traceId: z.string(),
    timestamp: z.string().datetime({ offset: true }),
    fieldErrors: z.array(fieldErrorSchema).optional(),
  })
  .strip() satisfies z.ZodType<ApiError>;

export type ApiErrorInput = z.infer<typeof apiErrorSchema>;
