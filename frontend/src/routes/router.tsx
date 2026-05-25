import { createRouter, createRoute, createRootRoute, Outlet } from '@tanstack/react-router';
import { AppProvider } from '@lib/app.provider';
import { ShLayoutComponent } from '@lib/components/sh-layout/layout.component';
import { JobsRoute } from './jobs/jobs.component';
import { ConnectionsRoute } from './connections/connections.component';
import { LogsRoute } from './logs/logs.component';
import { useAuthStore } from '@lib/store/auth.store';
import { getMe } from './auth/services/auth.service';

// Root Route
const rootRoute = createRootRoute({
  component: () => (
    <AppProvider>
      <div className="min-h-screen bg-background text-foreground font-sans antialiased">
        <Outlet />
      </div>
    </AppProvider>
  ),
});

// Layout Route (Protected)
const layoutRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: 'layout',
  component: ShLayoutComponent,
  beforeLoad: async () => {
    const { isAuthenticated, user, logout } = useAuthStore.getState();

    // Caso 1: Se não houver sessão ou dados do usuário (ex: F5 ou expiração), tenta recuperar via perfil
    if (!isAuthenticated || !user) {
      try {
        const profile = await getMe();
        validateAdminAccess(profile.roles, logout);
      } catch (error) {
        console.error('Falha na autenticação via cookie ou perfil inválido', error);
        logout();
        // Em Forward Auth, o gateway redirecionaria, mas aqui limpamos o estado
      }
      return;
    }

    // Caso 2: Já autenticado localmente, apenas garante que ainda é um administrador
    validateAdminAccess(user.roles, logout);
  },
});

/**
 * Valida se a lista de roles contém permissão de administrador.
 * Caso contrário, executa logout e lança erro para interromper a rota.
 */
function validateAdminAccess(roles: string[], logoutAction: () => void) {
  if (!roles.includes('ROLE_ADMIN')) {
    logoutAction();
    throw new Error('Acesso negado: Privilégios de administrador necessários');
  }
}

// Home (Jobs) Route
const homeRoute = createRoute({
  getParentRoute: () => layoutRoute,
  path: '/',
  component: JobsRoute,
});

// Connections Route
const connectionsRoute = createRoute({
  getParentRoute: () => layoutRoute,
  path: '/connections',
  component: ConnectionsRoute,
});

// Logs Route
const logsRoute = createRoute({
  getParentRoute: () => layoutRoute,
  path: '/logs',
  component: LogsRoute,
});

const routeTree = rootRoute.addChildren([
  layoutRoute.addChildren([homeRoute, connectionsRoute, logsRoute]),
]);

export const router = createRouter({ routeTree });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
