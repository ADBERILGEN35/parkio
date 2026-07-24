import { Suspense, createElement, type ReactElement } from 'react';
import {
  Navigate,
  createBrowserRouter,
  createMemoryRouter,
  type IndexRouteObject,
  type NonIndexRouteObject,
  type RouteObject,
} from 'react-router-dom';
import {
  ROUTE_MANIFEST,
  getRoutePath,
  type RouteId,
  type RouteManifestEntry,
} from './route-manifest';
import {
  ROUTE_ELEMENT_REGISTRY,
  ROUTE_FALLBACK_REGISTRY,
} from './route-elements';

export type AppRouter = ReturnType<typeof createBrowserRouter>;
export type AppBrowserRouterOptions = Parameters<
  typeof createBrowserRouter
>[1];
export type AppMemoryRouterOptions = Parameters<
  typeof createMemoryRouter
>[1];

function groupChildrenByParent(): ReadonlyMap<
  RouteId | null,
  readonly RouteManifestEntry[]
> {
  const childrenByParent = new Map<RouteId | null, RouteManifestEntry[]>();

  for (const entry of ROUTE_MANIFEST) {
    const children = childrenByParent.get(entry.parentId) ?? [];
    children.push(entry);
    childrenByParent.set(entry.parentId, children);
  }

  return childrenByParent;
}

function createRouteElement(entry: RouteManifestEntry): ReactElement {
  if (entry.redirect) {
    return (
      <Navigate
        to={getRoutePath(entry.redirect.targetId)}
        replace={entry.redirect.replace}
      />
    );
  }

  const registration = ROUTE_ELEMENT_REGISTRY[entry.componentKey];
  if (entry.load === 'lazy') {
    if (!registration.lazyComponent) {
      throw new Error(
        `Route '${entry.id}' requires a lazy element registration.`,
      );
    }
    const fallback = ROUTE_FALLBACK_REGISTRY[entry.fallback];
    if (!fallback) {
      throw new Error(`Lazy route '${entry.id}' requires a fallback.`);
    }
    return (
      <Suspense fallback={fallback}>
        {createElement(registration.lazyComponent)}
      </Suspense>
    );
  }

  if (!registration.eagerComponent) {
    throw new Error(
      `Route '${entry.id}' requires an eager element registration.`,
    );
  }
  return createElement(registration.eagerComponent);
}

function compileRoute(
  entry: RouteManifestEntry,
  childrenByParent: ReadonlyMap<
    RouteId | null,
    readonly RouteManifestEntry[]
  >,
): RouteObject {
  const element = createRouteElement(entry);

  if (entry.kind === 'index') {
    const route: IndexRouteObject = {
      id: entry.id,
      index: true,
      element,
    };
    return route;
  }

  const childEntries = childrenByParent.get(entry.id) ?? [];
  const children =
    childEntries.length > 0
      ? childEntries.map((child) => compileRoute(child, childrenByParent))
      : undefined;
  const route: NonIndexRouteObject = {
    id: entry.id,
    element,
    children,
  };

  if (entry.kind === 'path') {
    route.path = entry.path;
  }

  return route;
}

export function compileAppRoutes(): RouteObject[] {
  const childrenByParent = groupChildrenByParent();
  return (childrenByParent.get(null) ?? []).map((entry) =>
    compileRoute(entry, childrenByParent),
  );
}

export function createAppRouter(
  options?: AppBrowserRouterOptions,
): AppRouter {
  return createBrowserRouter(compileAppRoutes(), options);
}

export function createMemoryAppRouter(
  options?: AppMemoryRouterOptions,
): AppRouter {
  return createMemoryRouter(compileAppRoutes(), options);
}
