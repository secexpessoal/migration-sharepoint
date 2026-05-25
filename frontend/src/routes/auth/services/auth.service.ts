import apiClient from "@lib/utils/axios.util";
import { useAuthStore } from "@lib/store/auth.store";

export async function getMe() {
  const response = await apiClient.get("/auth/me");
  const userData = response.data;
  useAuthStore.getState().setUser(userData);
  return userData;
}

export async function logoutAttempt() {
  try {
    // 1. Backend limpa cookies HttpOnly e notifica o servidor de identidade
    await apiClient.post("/auth/logout");
  } catch (error) {
    console.error("Erro ao realizar logout no servidor:", error);
  } finally {
    // 2. Frontend limpa cookies visíveis (JS-accessible) como redundância
    clearAllClientCookies();

    // 3. Limpa o estado local (Zustand)
    useAuthStore.getState().logout();

    // 4. Força o recarregamento total da aplicação
    window.location.reload();
  }
}

/**
 * Remove agressivamente todos os cookies visíveis ao JavaScript.
 * Utiliza um estilo funcional e tenta múltiplas combinações de domínios e caminhos.
 */
function clearAllClientCookies() {
  const hostname = window.location.hostname;
  const domainParts = hostname.split('.');
  
  // Lista de domínios para tentar a limpeza (atual, .atual, e pai se existir)
  const domains = [null, hostname, `.${hostname}`];
  if (domainParts.length > 2) {
    domains.push(domainParts.slice(-2).join('.'));
    domains.push(`.${domainParts.slice(-2).join('.')}`);
  }

  // Caminhos comuns onde cookies podem ser injetados
  const paths = ['/', '/v1', '/api'];

  document.cookie.split(';').forEach(cookie => {
    const name = cookie.split('=')[0].trim();
    
    domains.forEach(domain => {
      paths.forEach(path => {
        let cookieString = `${name}=; Path=${path}; Expires=Thu, 01 Jan 1970 00:00:00 GMT;`;
        if (domain) cookieString += ` Domain=${domain};`;
        document.cookie = cookieString;
      });
    });
  });
}
