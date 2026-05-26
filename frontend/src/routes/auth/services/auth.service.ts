import apiClient from "@lib/utils/axios.util";
import { useAuthStore } from "@lib/store/auth.store";

export async function getMe() {
  const response = await apiClient.get("/auth/me");
  const userData = response.data;
  useAuthStore.getState().setUser(userData);
  return userData;
}

export async function logoutAttempt() {
  console.log("Iniciando logoutAttempt no frontend...");
  try {
    // 1. Backend limpa cookies HttpOnly e notifica o servidor de identidade
    console.log("Chamando endpoint de logout no backend...");
    await apiClient.post("/auth/logout");
    console.log("Resposta do backend recebida com sucesso.");
  } catch (error) {
    console.error("Erro ao realizar logout no servidor:", error);
  } finally {
    console.log("Executando limpeza local de cookies e estado...");
    // 2. Frontend limpa cookies visíveis (JS-accessible) como redundância
    clearAllClientCookies();

    // 3. Limpa o estado local (Zustand)
    useAuthStore.getState().logout();

    // 4. Redirecionamento total para a raiz
    console.log("Redirecionando para a raiz...");
    window.location.replace("/");
  }
}

/**
 * Remove agressivamente todos os cookies visíveis ao JavaScript.
 * Utiliza um estilo funcional e inclui cookies padrão de sessão (__session, XSRF-TOKEN).
 */
function clearAllClientCookies() {
  const hostname = window.location.hostname;
  const domainParts = hostname.split('.');
  
  // Lista de domínios para tentar a limpeza (atual, .atual, e pai se existir)
  const domains = [null, hostname, `.${hostname}`];
  if (domainParts.length > 2) {
    const parentDomain = domainParts.slice(-2).join('.');
    domains.push(parentDomain);
    domains.push(`.${parentDomain}`);
  }

  // Caminhos comuns onde cookies podem ser injetados
  const paths = ['/', '/v1', '/api'];

  // Nomes de cookies para limpar (mesmos do backend)
  const cookieNamesToClear = ['access_token', 'refresh_token', '__session', 'XSRF-TOKEN'];

  // 1. Limpa cookies que ele encontra no document.cookie
  document.cookie.split(';').forEach(cookie => {
    const name = cookie.split('=')[0].trim();
    applyClearance(name, domains, paths);
  });

  // 2. Garante a limpeza dos nomes conhecidos mesmo que não estejam listados (failsafe)
  cookieNamesToClear.forEach(name => applyClearance(name, domains, paths));
}

function applyClearance(name: string, domains: (string | null)[], paths: string[]) {
  domains.forEach(domain => {
    paths.forEach(path => {
      let cookieString = `${name}=; Path=${path}; Expires=Thu, 01 Jan 1970 00:00:00 GMT;`;
      if (domain) cookieString += ` Domain=${domain};`;
      document.cookie = cookieString;
    });
  });
}
