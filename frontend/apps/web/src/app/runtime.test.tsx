import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { QueryClient } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import { StrictMode } from 'react';
import { useLocation } from 'react-router-dom';
import ts from 'typescript';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { App } from '@/App';
import { MemoryOnlyTokenStorage } from '@/platform/browser/token-storage';
import { createMemoryAppRouter } from '@/routing/create-app-router';
import {
  renderWithProviders as renderWithTestProviders,
} from '@/test/utils';
import { useParkioSdk } from './AppRuntimeContext';
import { AppRuntimeProvider } from './AppRuntimeProvider';
import { createWebAppRuntime, type WebAppRuntime } from './runtime';

const user = {
  id: '6f9619ff-8b86-4d01-b42d-00cf4fc964ff',
  email: 'tester@parkio.dev',
  status: 'ACTIVE',
  roles: ['USER'],
};

const runtimes: WebAppRuntime[] = [];
const WEB_SOURCE_ROOT = dirname(dirname(fileURLToPath(import.meta.url)));

/** True for sources under src/test; uses path.relative so Windows separators match. */
function isTestHelperSource(path: string): boolean {
  const rel = relative(WEB_SOURCE_ROOT, path);
  return rel === 'test' || rel.startsWith(`test${sep}`);
}

afterEach(() => {
  runtimes.splice(0).reverse().forEach((runtime) => runtime.dispose());
});

function createRuntime(initialEntries: string[] = ['/login']) {
  const runtime = createWebAppRuntime({
    baseURL: 'http://localhost/api/v1',
    queryClient: new QueryClient(),
    createRouter: () => createMemoryAppRouter({ initialEntries }),
  });
  runtimes.push(runtime);
  return runtime;
}

function collectSourceFiles(root: string): string[] {
  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const path = join(root, entry.name);
    if (entry.isDirectory()) {
      return collectSourceFiles(path);
    }
    return /\.(?:ts|tsx)$/.test(entry.name) ? [path] : [];
  });
}

function parseSourceFile(path: string) {
  const source = readFileSync(path, 'utf8');
  return ts.createSourceFile(
    path,
    source,
    ts.ScriptTarget.Latest,
    true,
    path.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
}

function hasModuleScopeBrowserRouterConstruction(path: string): boolean {
  const sourceFile = parseSourceFile(path);
  const directImports = new Set<string>();
  const namespaceImports = new Set<string>();

  for (const statement of sourceFile.statements) {
    if (
      !ts.isImportDeclaration(statement) ||
      !ts.isStringLiteral(statement.moduleSpecifier) ||
      statement.moduleSpecifier.text !== 'react-router-dom'
    ) {
      continue;
    }
    const bindings = statement.importClause?.namedBindings;
    if (bindings && ts.isNamedImports(bindings)) {
      for (const binding of bindings.elements) {
        if ((binding.propertyName ?? binding.name).text === 'createBrowserRouter') {
          directImports.add(binding.name.text);
        }
      }
    } else if (bindings && ts.isNamespaceImport(bindings)) {
      namespaceImports.add(bindings.name.text);
    }
  }

  let found = false;
  function visit(node: ts.Node, functionDepth: number) {
    if (found) return;
    if (functionDepth === 0 && ts.isCallExpression(node)) {
      const target = node.expression;
      if (
        (ts.isIdentifier(target) && directImports.has(target.text)) ||
        (ts.isPropertyAccessExpression(target) &&
          ts.isIdentifier(target.expression) &&
          namespaceImports.has(target.expression.text) &&
          target.name.text === 'createBrowserRouter') ||
        (ts.isElementAccessExpression(target) &&
          ts.isIdentifier(target.expression) &&
          namespaceImports.has(target.expression.text) &&
          ts.isStringLiteral(target.argumentExpression) &&
          target.argumentExpression.text === 'createBrowserRouter')
      ) {
        found = true;
        return;
      }
    }

    const childDepth = ts.isFunctionLike(node)
      ? functionDepth + 1
      : functionDepth;
    ts.forEachChild(node, (child) => visit(child, childDepth));
  }

  for (const statement of sourceFile.statements) {
    visit(statement, 0);
  }
  return found;
}

function hasRuntimeCreationWithoutRouterFactory(path: string): boolean {
  const sourceFile = parseSourceFile(path);
  const runtimeFactories = new Set<string>();

  for (const statement of sourceFile.statements) {
    if (
      !ts.isImportDeclaration(statement) ||
      !ts.isStringLiteral(statement.moduleSpecifier)
    ) {
      continue;
    }
    const bindings = statement.importClause?.namedBindings;
    if (!bindings || !ts.isNamedImports(bindings)) continue;
    for (const binding of bindings.elements) {
      if ((binding.propertyName ?? binding.name).text === 'createWebAppRuntime') {
        runtimeFactories.add(binding.name.text);
      }
    }
  }

  let found = false;
  function visit(node: ts.Node) {
    if (
      ts.isCallExpression(node) &&
      ts.isIdentifier(node.expression) &&
      runtimeFactories.has(node.expression.text)
    ) {
      const options = node.arguments[0];
      const injectsRouter =
        options &&
        ts.isObjectLiteralExpression(options) &&
        options.properties.some((property) => {
          if (ts.isShorthandPropertyAssignment(property)) {
            return property.name.text === 'createRouter';
          }
          if (
            !ts.isPropertyAssignment(property) &&
            !ts.isMethodDeclaration(property)
          ) {
            return false;
          }
          return (
            (ts.isIdentifier(property.name) &&
              property.name.text === 'createRouter') ||
            (ts.isStringLiteral(property.name) &&
              property.name.text === 'createRouter')
          );
        });
      if (!injectsRouter) {
        found = true;
        return;
      }
    }
    if (!found) ts.forEachChild(node, visit);
  }

  visit(sourceFile);
  return found;
}

function routerProviderCount(path: string): number {
  const sourceFile = parseSourceFile(path);
  const providerImports = new Set<string>();

  for (const statement of sourceFile.statements) {
    if (
      !ts.isImportDeclaration(statement) ||
      !ts.isStringLiteral(statement.moduleSpecifier) ||
      statement.moduleSpecifier.text !== 'react-router-dom'
    ) {
      continue;
    }
    const bindings = statement.importClause?.namedBindings;
    if (!bindings || !ts.isNamedImports(bindings)) continue;
    for (const binding of bindings.elements) {
      if ((binding.propertyName ?? binding.name).text === 'RouterProvider') {
        providerImports.add(binding.name.text);
      }
    }
  }

  let count = 0;
  function visit(node: ts.Node) {
    if (
      (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) &&
      ts.isIdentifier(node.tagName) &&
      providerImports.has(node.tagName.text)
    ) {
      count += 1;
    }
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);
  return count;
}

describe('Web application composition', () => {
  it('creates isolated router, SDK, Query Client, and authentication ownership per runtime', () => {
    const first = createRuntime();
    first.dispose();
    const second = createRuntime();

    expect(first).not.toBe(second);
    expect(first.router).not.toBe(second.router);
    expect(first.sdk).not.toBe(second.sdk);
    expect(first.queryClient).not.toBe(second.queryClient);
    expect(first.authStore).not.toBe(second.authStore);
    expect(first.authSession).not.toBe(second.authSession);
    expect('transport' in first.sdk).toBe(false);
  });

  it('keeps authentication mutations isolated to their owning runtime', () => {
    const first = createRuntime();
    const second = createRuntime();

    first.authStore.getState().setSession('first-token', user);

    expect(first.authStore.getState().accessToken).toBe('first-token');
    expect(second.authStore.getState().accessToken).toBeNull();
    expect(second.authStore.getState().isAuthenticated).toBe(false);
  });

  it('wires the application auth state to its scoped browser token adapter', () => {
    const tokenStorage = new MemoryOnlyTokenStorage();
    const runtime = createWebAppRuntime({
      baseURL: 'http://localhost/api/v1',
      queryClient: new QueryClient(),
      tokenStorage,
      createRouter: () =>
        createMemoryAppRouter({ initialEntries: ['/login'] }),
    });
    runtimes.push(runtime);

    runtime.authStore.getState().setSession('scoped-token', user);
    expect(tokenStorage.getAccessToken()).toBe('scoped-token');

    runtime.authStore.getState().clearSession();
    expect(tokenStorage.getAccessToken()).toBeNull();
  });

  it('exposes the identity-transition boundary without coupling it to query cache behavior', () => {
    const runtime = createRuntime();
    const listener = vi.fn();
    runtime.authStore.subscribeIdentityChanges(listener);

    runtime.authStore.getState().setSession('scoped-token', user);
    runtime.authSession.destroyLocalSession();

    expect(listener).toHaveBeenCalledTimes(2);
    expect(listener.mock.calls[0]?.[0].current.state).toBe('authenticated');
    expect(listener.mock.calls[1]?.[0].current.state).toBe('anonymous');
    expect(JSON.stringify(listener.mock.calls)).not.toContain('scoped-token');
  });

  it('exposes domain clients through the provider without exposing raw transport', () => {
    const runtime = createRuntime();

    function Consumer() {
      const sdk = useParkioSdk();
      return <span>{typeof sdk.parkingApi.getNearbySpots}</span>;
    }

    render(
      <AppRuntimeProvider runtime={runtime}>
        <Consumer />
      </AppRuntimeProvider>,
    );

    expect(screen.getByText('function')).toBeInTheDocument();
  });

  it('creates independent Memory routers for tests without touching browser history', () => {
    window.history.pushState({}, '', '/terms');
    const first = createRuntime(['/privacy']);
    const second = createRuntime(['/login']);

    expect(first.router).not.toBe(second.router);
    expect(first.router.routes).not.toBe(second.router.routes);
    expect(first.router.state.location.pathname).toBe('/privacy');
    expect(second.router.state.location.pathname).toBe('/login');
    expect(window.location.pathname).toBe('/terms');
  });

  it('constructs one router per runtime and reuses it across App renders', async () => {
    window.history.pushState({}, '', '/login');
    const createRouter = vi.fn(() =>
      createMemoryAppRouter({ initialEntries: ['/login'] }),
    );
    const runtime = createWebAppRuntime({
      baseURL: 'http://localhost/api/v1',
      queryClient: new QueryClient(),
      createRouter,
    });
    runtimes.push(runtime);
    const ownedRouter = runtime.router;
    const subscribe = vi.spyOn(ownedRouter, 'subscribe');

    const view = render(<App runtime={runtime} />);

    expect(
      await screen.findByRole('heading', { name: 'Welcome back' }),
    ).toBeInTheDocument();
    expect(createRouter).toHaveBeenCalledTimes(1);
    expect(subscribe).toHaveBeenCalledTimes(1);
    expect(runtime.router).toBe(ownedRouter);

    view.rerender(<App runtime={runtime} />);

    expect(createRouter).toHaveBeenCalledTimes(1);
    expect(subscribe).toHaveBeenCalledTimes(1);
    expect(runtime.router).toBe(ownedRouter);
  });

  it('keeps runtime-owned router construction stable under Strict Mode', async () => {
    window.history.pushState({}, '', '/login');
    const createRouter = vi.fn(() =>
      createMemoryAppRouter({ initialEntries: ['/login'] }),
    );
    const runtime = createWebAppRuntime({
      baseURL: 'http://localhost/api/v1',
      queryClient: new QueryClient(),
      createRouter,
    });
    runtimes.push(runtime);
    const ownedRouter = runtime.router;

    render(
      <StrictMode>
        <App runtime={runtime} />
      </StrictMode>,
    );

    expect(
      await screen.findByRole('heading', { name: 'Welcome back' }),
    ).toBeInTheDocument();
    expect(createRouter).toHaveBeenCalledTimes(1);
    expect(runtime.router).toBe(ownedRouter);
  });

  it('disposes its router exactly once through idempotent runtime teardown', () => {
    const runtime = createRuntime();
    const disposeRouter = vi.spyOn(runtime.router, 'dispose');

    runtime.dispose();
    runtime.dispose();

    expect(disposeRouter).toHaveBeenCalledTimes(1);
  });

  it('never shares navigation history between runtimes', async () => {
    const first = createRuntime(['/login']);
    const second = createRuntime(['/privacy']);

    await first.router.navigate('/terms');

    expect(first.router.state.location.pathname).toBe('/terms');
    expect(second.router.state.location.pathname).toBe('/privacy');
  });

  it('mounts shared test content through the runtime-owned router only', async () => {
    function LocationProbe() {
      const location = useLocation();
      return <span>{`${location.pathname}${location.search}`}</span>;
    }

    const view = renderWithTestProviders(<LocationProbe />, {
      initialEntries: ['/owned?source=runtime'],
    });
    const disposeRouter = vi.spyOn(view.runtime.router, 'dispose');

    expect(view.router).toBe(view.runtime.router);
    expect(screen.getByText('/owned?source=runtime')).toBeInTheDocument();

    await act(() => view.runtime.router.navigate('/next'));

    expect(screen.getByText('/next')).toBeInTheDocument();

    view.unmount();
    expect(disposeRouter).toHaveBeenCalledTimes(1);
  });

  it('contains no production module-scope browser router construction', () => {
    const violations = collectSourceFiles(WEB_SOURCE_ROOT)
      .filter((path) => !isTestHelperSource(path) && !/\.test\.(?:ts|tsx)$/.test(path))
      .filter(hasModuleScopeBrowserRouterConstruction)
      .map((path) => path.slice(WEB_SOURCE_ROOT.length + 1));

    expect(violations).toEqual([]);
  });

  it('injects a runtime-owned router factory into every Web test runtime', () => {
    const violations = collectSourceFiles(WEB_SOURCE_ROOT)
      .filter(
        (path) =>
          /\.test\.(?:ts|tsx)$/.test(path) ||
          path === join(WEB_SOURCE_ROOT, 'test', 'utils.tsx'),
      )
      .filter(hasRuntimeCreationWithoutRouterFactory)
      .map((path) => path.slice(WEB_SOURCE_ROOT.length + 1));

    expect(violations).toEqual([]);
  });

  it('keeps the sole production RouterProvider in App', () => {
    const owners = collectSourceFiles(WEB_SOURCE_ROOT)
      .filter((path) => !isTestHelperSource(path) && !/\.test\.(?:ts|tsx)$/.test(path))
      .map((path) => ({
        count: routerProviderCount(path),
        path: path.slice(WEB_SOURCE_ROOT.length + 1),
      }))
      .filter(({ count }) => count > 0);

    expect(owners).toEqual([{ count: 1, path: 'App.tsx' }]);
  });
});
