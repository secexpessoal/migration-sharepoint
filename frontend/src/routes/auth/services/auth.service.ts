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
 * Tenta remover todos os cookies visíveis ao JavaScript.
 * Útil para cookies que não foram marcados como HttpOnly por proxies ou dev servers.
 */
function clearAllClientCookies() {
  const cookies = document.cookie.split(";");

  for (let i = 0; i < cookies.length; i++) {
    const cookie = cookies[i];
    const eqPos = cookie.indexOf("=");
    const name = eqPos > -1 ? cookie.substring(0, eqPos).trim() : cookie.trim();
    
    // Tenta deletar o cookie no path raiz e em variações de domínio
    document.cookie = `${name}=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT;`;
    document.cookie = `${name}=; Path=/; Domain=${window.location.hostname}; Expires=Thu, 01 Jan 1970 00:00:00 GMT;`;
    
    // Se estiver em um subdomínio, tenta limpar no domínio pai também
    const domainParts = window.location.hostname.split('.');
    if (domainParts.length > 2) {
      const parentDomain = domainParts.slice(-2).join('.');
      document.cookie = `${name}=; Path=/; Domain=.${parentDomain}; Expires=Thu, 01 Jan 1970 00:00:00 GMT;`;
    }
  }
}
