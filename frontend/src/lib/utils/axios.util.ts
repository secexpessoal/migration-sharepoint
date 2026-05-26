import axios from 'axios';
import { useAuthStore } from '../store/auth.store';

const apiClient = axios.create({
  baseURL: '/v1',
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true,
});

// Response interceptor to handle errors
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    // Se for 403 ou 401, o usuário provavelmente perdeu a sessão
    if (error.response?.status === 401 || error.response?.status === 403) {
      useAuthStore.getState().logout();
      // Não redirecionamos para /login pois ele não existe mais. 
      // Em um cenário de Forward Auth, o gateway ou o F5 lidará com isso.
    }
    return Promise.reject(error);
  }
);

export default apiClient;
