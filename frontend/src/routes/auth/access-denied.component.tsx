import { ShieldAlert, LogOut } from 'lucide-react';
import { ShButton } from '@lib/components/sh-button/button.component';
import { ShCard } from '@lib/components/sh-card/card.component';
import { logoutAttempt } from './services/auth.service';

export const AccessDeniedComponent = () => {
  const handleLogout = async () => {
    await logoutAttempt();
  };

  return (
    <div className="flex items-center justify-center min-h-screen bg-muted/30 p-4">
      <ShCard className="max-w-md w-full p-8 text-center space-y-6 shadow-lg">
        <div className="flex justify-center">
          <div className="p-4 bg-destructive/10 rounded-full">
            <ShieldAlert className="w-12 h-12 text-destructive" />
          </div>
        </div>
        
        <div className="space-y-2">
          <h1 className="text-2xl font-bold tracking-tight">Acesso Negado</h1>
          <p className="text-muted-foreground">
            Sua conta não possui privilégios de administrador necessários para acessar este sistema.
          </p>
        </div>

        <div className="pt-4 border-t space-y-4">
          <p className="text-sm text-muted-foreground">
            Se você acredita que isso é um erro, entre em contato com o suporte de TI.
          </p>
          
          <ShButton 
            variant="outline" 
            className="w-full gap-2"
            onClick={handleLogout}
          >
            <LogOut className="w-4 h-4" />
            Sair e trocar de conta
          </ShButton>
        </div>
      </ShCard>
    </div>
  );
};
