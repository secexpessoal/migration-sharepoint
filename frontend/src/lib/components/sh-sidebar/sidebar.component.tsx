import { Link } from '@tanstack/react-router';
import { LayoutDashboard, Database, History, LogOut } from 'lucide-react';
import { ShButton } from '@lib/components/sh-button/button.component';
import { useAuthStore } from '@lib/store/auth.store';
import { logoutAttempt } from '@/routes/auth/services/auth.service';
import { toast } from 'sonner';

interface ShSidebarProps {
  onNavigate?: () => void;
}

export const ShSidebar = ({ onNavigate }: ShSidebarProps) => {
  const logoutAction = useAuthStore((state) => state.logout);

  const handleLogout = async () => {
    try {
      await logoutAttempt();
      // logoutAttempt já executa window.location.reload()
      toast.success('Sessão encerrada com sucesso.');
    } catch (error) {
      // Caso ocorra erro no request, limpamos o estado local e recarregamos manualmente
      console.error('Falha ao realizar logout no servidor:', error);
      logoutAction();
      window.location.reload();
    }
  };

  return (
    <aside className="w-64 border-r bg-muted/30 flex flex-col h-full">
      <div className="p-6 border-b">
        <h1 className="text-xl font-bold flex items-center gap-2">
          <Database className="w-6 h-6 text-primary" />
          <span>SP Migrator</span>
        </h1>
      </div>

      <nav className="flex-1 p-4 space-y-1 overflow-y-auto">
        <Link
          to="/"
          activeProps={{ className: 'bg-primary/10 text-primary font-medium' }}
          className="flex items-center gap-3 px-4 py-2 rounded-md transition-colors hover:bg-muted text-sm"
          onClick={onNavigate}
        >
          <LayoutDashboard className="w-4 h-4 shrink-0" />
          <span>Jobs</span>
        </Link>

        <Link
          to="/connections"
          activeProps={{ className: 'bg-primary/10 text-primary font-medium' }}
          className="flex items-center gap-3 px-4 py-2 rounded-md transition-colors hover:bg-muted text-sm"
          onClick={onNavigate}
        >
          <Database className="w-4 h-4 shrink-0" />
          <span>Connections</span>
        </Link>

        <Link
          to="/logs"
          activeProps={{ className: 'bg-primary/10 text-primary font-medium' }}
          className="flex items-center gap-3 px-4 py-2 rounded-md transition-colors hover:bg-muted text-sm"
          onClick={onNavigate}
        >
          <History className="w-4 h-4 shrink-0" />
          <span>Logs</span>
        </Link>
      </nav>

      <div className="p-4 border-t">
        <ShButton 
          variant="ghost" 
          className="w-full justify-start gap-3 text-sm text-destructive hover:text-destructive hover:bg-destructive/10"
          onClick={handleLogout}
        >
          <LogOut className="w-4 h-4 shrink-0" />
          <span>Sair</span>
        </ShButton>
      </div>
    </aside>
  );
};
