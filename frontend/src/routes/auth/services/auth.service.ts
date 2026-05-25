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
    await apiClient.post("/auth/logout");
    useAuthStore.getState().logout();
    // Força um F5 para limpar qualquer estado e cookies remanescentes no navegador
    window.location.reload();
  } catch (error) {
    console.error("Erro ao realizar logout no servidor:", error);
    throw error;
  }
}
