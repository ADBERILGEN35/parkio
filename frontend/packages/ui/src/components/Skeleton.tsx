import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '../cn';
import { Card } from './Card';
import { Surface } from './Surface';

export interface SkeletonBlockProps extends HTMLAttributes<HTMLDivElement> {
  rounded?: 'sm' | 'md' | 'lg' | 'xl' | '2xl' | '3xl' | 'full';
}

const RADIUS_CLASSES: Record<NonNullable<SkeletonBlockProps['rounded']>, string> = {
  sm: 'rounded',
  md: 'rounded-lg',
  lg: 'rounded-xl',
  xl: 'rounded-2xl',
  '2xl': 'rounded-2xl',
  '3xl': 'rounded-3xl',
  full: 'rounded-full',
};

export function SkeletonBlock({ className, rounded = 'md', ...props }: SkeletonBlockProps) {
  return (
    <div
      aria-hidden
      className={cn('skeleton-shimmer bg-surface-container-high', RADIUS_CLASSES[rounded], className)}
      {...props}
    />
  );
}

export function SkeletonText({
  lines = 1,
  className,
}: {
  lines?: number;
  className?: string;
}) {
  return (
    <div className={cn('flex flex-col gap-xs', className)} aria-hidden>
      {Array.from({ length: lines }).map((_, index) => (
        <SkeletonBlock
          key={index}
          className={cn('h-3', index === lines - 1 && lines > 1 ? 'w-2/3' : 'w-full')}
          rounded="full"
        />
      ))}
    </div>
  );
}

function CardFrame({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cn('rounded-2xl border border-outline-variant/20 bg-surface-container-lowest p-lg shadow-soft', className)}>
      {children}
    </div>
  );
}

export function SpotCardSkeleton({ className }: { className?: string }) {
  return (
    <CardFrame className={cn('rounded-[1.5rem] p-md', className)}>
      <div className="flex items-start justify-between gap-sm">
        <div className="min-w-0 flex-1">
          <SkeletonBlock className="h-4 w-4/5" rounded="full" />
          <SkeletonBlock className="mt-sm h-3 w-1/2" rounded="full" />
        </div>
        <SkeletonBlock className="h-6 w-20 shrink-0" rounded="full" />
      </div>
      <SkeletonBlock className="mt-sm h-3 w-3/5" rounded="full" />
      <SkeletonBlock className="mt-xs h-3 w-4/5" rounded="full" />
      <div className="mt-sm flex flex-wrap gap-xs">
        <SkeletonBlock className="h-6 w-16" rounded="full" />
        <SkeletonBlock className="h-6 w-20" rounded="full" />
        <SkeletonBlock className="h-6 w-24" rounded="full" />
      </div>
    </CardFrame>
  );
}

export function SpotDetailSkeleton() {
  return (
    <div className="flex flex-col gap-lg lg:flex-row lg:items-start" role="status" aria-label="Loading spot details">
      <div className="contents lg:flex lg:min-w-0 lg:flex-1 lg:flex-col lg:gap-lg">
        <SkeletonBlock className="order-1 aspect-[4/3] w-full shadow-deep md:aspect-[16/9]" rounded="3xl" />
        <div className="order-3 flex flex-col gap-lg lg:order-none">
          <CardFrame className="rounded-3xl">
            <SkeletonBlock className="h-5 w-1/3" rounded="full" />
            <SkeletonText lines={3} className="mt-md" />
          </CardFrame>
          <CardFrame className="rounded-3xl">
            <div className="grid grid-cols-2 gap-sm">
              {Array.from({ length: 4 }).map((_, index) => (
                <SkeletonBlock key={index} className="h-16" rounded="2xl" />
              ))}
            </div>
          </CardFrame>
          <CardFrame className="rounded-3xl">
            <SkeletonText lines={4} />
          </CardFrame>
        </div>
        <SkeletonBlock className="order-4 h-80 w-full lg:order-none" rounded="3xl" />
      </div>
      <aside className="order-2 flex w-full shrink-0 flex-col gap-lg lg:order-none lg:w-[400px]">
        <CardFrame className="rounded-3xl shadow-deep">
          <div className="flex gap-xs">
            <SkeletonBlock className="h-6 w-20" rounded="full" />
            <SkeletonBlock className="h-6 w-24" rounded="full" />
          </div>
          <SkeletonBlock className="mt-md h-7 w-5/6" rounded="full" />
          <SkeletonBlock className="mt-xs h-3 w-2/3" rounded="full" />
          <div className="mt-lg grid grid-cols-2 gap-sm">
            {Array.from({ length: 4 }).map((_, index) => (
              <SkeletonBlock key={index} className="h-16" rounded="2xl" />
            ))}
          </div>
        </CardFrame>
        <CardFrame className="rounded-3xl">
          <SkeletonBlock className="h-5 w-1/2" rounded="full" />
          <SkeletonText lines={5} className="mt-md" />
        </CardFrame>
      </aside>
    </div>
  );
}

export function NotificationSkeleton({ count = 4 }: { count?: number }) {
  return (
    <Surface level="card" className="flex flex-col gap-md p-md md:p-lg" role="status" aria-label="Loading notifications">
      {Array.from({ length: count }).map((_, index) => (
        <div key={index} className="flex gap-sm rounded-xl bg-surface-container-low/50 p-sm">
          <SkeletonBlock className="h-9 w-9 shrink-0" rounded="full" />
          <div className="min-w-0 flex-1">
            <SkeletonBlock className="h-4 w-2/3" rounded="full" />
            <SkeletonBlock className="mt-xs h-3 w-full" rounded="full" />
            <div className="mt-sm flex gap-xs">
              <SkeletonBlock className="h-5 w-20" rounded="full" />
              <SkeletonBlock className="h-5 w-28" rounded="full" />
            </div>
          </div>
        </div>
      ))}
    </Surface>
  );
}

export function ProfileSkeleton() {
  return (
    <div className="flex flex-col gap-lg" role="status" aria-label="Loading profile">
      <CardFrame>
        <div className="flex items-center gap-md">
          <SkeletonBlock className="h-16 w-16 shrink-0" rounded="full" />
          <div className="min-w-0 flex-1">
            <SkeletonBlock className="h-6 w-1/2" rounded="full" />
            <div className="mt-sm flex gap-xs">
              <SkeletonBlock className="h-6 w-20" rounded="full" />
              <SkeletonBlock className="h-6 w-24" rounded="full" />
            </div>
          </div>
        </div>
        <div className="mt-lg grid grid-cols-2 gap-md lg:grid-cols-4">
          {Array.from({ length: 4 }).map((_, index) => (
            <SkeletonBlock key={index} className="h-24" rounded="xl" />
          ))}
        </div>
      </CardFrame>
      <Card title="Profile">
        <div className="flex flex-col gap-md">
          <SkeletonBlock className="h-16" rounded="2xl" />
          <SkeletonBlock className="h-16" rounded="lg" />
          <SkeletonBlock className="h-16" rounded="lg" />
          <SkeletonBlock className="h-16" rounded="lg" />
          <SkeletonBlock className="h-10 w-32" rounded="full" />
        </div>
      </Card>
    </div>
  );
}

export function LeaderboardSkeleton() {
  return (
    <div className="flex flex-col gap-lg" role="status" aria-label="Loading leaderboard">
      <Surface level="raised" className="rounded-3xl p-lg">
        <SkeletonBlock className="h-4 w-36" rounded="full" />
        <div className="mt-md grid grid-cols-1 gap-md sm:grid-cols-3">
          {Array.from({ length: 3 }).map((_, index) => (
            <SkeletonBlock key={index} className="h-24" rounded="xl" />
          ))}
        </div>
      </Surface>
      <div className="grid grid-cols-3 items-end gap-sm">
        {Array.from({ length: 3 }).map((_, index) => (
          <SkeletonBlock
            key={index}
            className={cn('w-full rounded-3xl', index === 1 ? 'h-48' : 'h-36')}
            rounded="3xl"
          />
        ))}
      </div>
      <Surface level="card" className="rounded-3xl p-md">
        <div className="flex flex-col gap-sm">
          {Array.from({ length: 5 }).map((_, index) => (
            <div key={index} className="flex items-center gap-sm rounded-2xl bg-surface-container-low p-md">
              <SkeletonBlock className="h-10 w-10" rounded="full" />
              <div className="min-w-0 flex-1">
                <SkeletonBlock className="h-4 w-1/2" rounded="full" />
                <SkeletonBlock className="mt-xs h-3 w-1/3" rounded="full" />
              </div>
              <SkeletonBlock className="h-6 w-20" rounded="full" />
            </div>
          ))}
        </div>
      </Surface>
    </div>
  );
}

export function MapSearchSkeleton() {
  return (
    <div className="fixed inset-x-0 bottom-16 top-16 z-0 overflow-hidden bg-background md:bottom-0" role="status" aria-label="Loading map">
      <SkeletonBlock className="absolute inset-0 h-full w-full" rounded="sm" />
      <div className="pointer-events-none absolute inset-x-0 top-md z-[1100] flex justify-center px-sm md:justify-start md:px-md md:pl-lg">
        <div className="pointer-events-auto w-full max-w-[min(28rem,calc(100vw-1rem))] rounded-2xl border border-outline-variant/20 bg-surface/80 p-md shadow-deep backdrop-blur-xl">
          <SkeletonBlock className="h-5 w-32" rounded="full" />
          <SkeletonBlock className="mt-sm h-3 w-4/5" rounded="full" />
          <SkeletonBlock className="mt-md h-12 w-full" rounded="lg" />
          <SkeletonBlock className="mt-sm h-8 w-36" rounded="full" />
        </div>
      </div>
      <aside className="pointer-events-none absolute inset-x-0 bottom-[calc(4rem+env(safe-area-inset-bottom))] z-[1050] flex max-h-[44dvh] flex-col md:bottom-0 md:left-auto md:right-0 md:top-0 md:max-h-none md:w-[400px]">
        <div className="pointer-events-auto flex h-full min-h-0 flex-col overflow-hidden rounded-t-3xl border border-outline-variant/20 bg-surface/80 p-md shadow-sheet-left backdrop-blur-xl md:rounded-none md:rounded-l-[2rem]">
          <div className="mx-auto mb-md h-1.5 w-12 shrink-0 rounded-full bg-outline-variant md:hidden" />
          <div className="flex flex-col gap-sm">
            <SpotCardSkeleton />
            <SpotCardSkeleton />
          </div>
        </div>
      </aside>
    </div>
  );
}
