import type { LeaderboardEntry, PublicProfile } from '@parkio/types';
import { SoftBadge, cn } from '@parkio/ui';
import { humanizeEnum } from '@/lib/format';
import { trustBandTone } from '@/pages/profile/accountVisuals';

export function shortId(userId: string): string {
  return `${userId.slice(0, 8)}…`;
}

export function labelFor(userId: string, profile?: PublicProfile | null): string {
  return profile?.displayName?.trim() || shortId(userId);
}

export function initialsFor(label: string): string {
  const trimmed = label.trim();
  if (!trimmed) return '?';
  const parts = trimmed.split(/\s+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0]![0]! + parts[1]![0]!).toUpperCase();
  return trimmed.slice(0, 2).toUpperCase();
}

export interface LeaderboardRowProps {
  entry: LeaderboardEntry;
  profile: PublicProfile | null;
  isMe: boolean;
}

export function LeaderboardRow({ entry, profile, isMe }: LeaderboardRowProps) {
  const label = labelFor(entry.userId, profile);
  const band = profile?.trustBand?.trim() || null;

  return (
    <li
      className={cn(
        'flex items-center gap-md border-b border-outline-variant/20 px-md py-sm last:border-b-0 transition-colors duration-std',
        isMe ? 'border-l-4 border-l-primary bg-primary/5' : 'hover:bg-surface-container-low',
      )}
    >
      <span className="w-6 shrink-0 text-center text-body-md font-semibold text-on-surface-variant">
        {entry.rank}
      </span>

      <span
        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary-container text-label-md font-bold text-on-primary-container"
        aria-hidden
      >
        {initialsFor(label)}
      </span>

      <span className="min-w-0 flex-1 truncate text-body-md text-on-surface" title={label}>
        {label}
        {isMe ? <span className="ml-sm text-label-sm text-primary">(you)</span> : null}
      </span>

      {band ? <SoftBadge tone={trustBandTone(band)}>{humanizeEnum(band)}</SoftBadge> : null}

      <SoftBadge tone="neutral">Level {entry.currentLevel}</SoftBadge>

      <span className="shrink-0 text-body-md font-semibold text-on-surface">
        {entry.totalPoints} pts
      </span>
    </li>
  );
}
