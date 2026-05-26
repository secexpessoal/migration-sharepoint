import { create } from 'zustand';
import { persist } from 'zustand/middleware';

interface UserProfile {
  id: string;
  email: string;
  active: boolean;
  roles: string[];
  profile: {
    username: string;
    registration: string;
    position: string;
  };
}

interface AuthState {
  isAuthenticated: boolean;
  user: UserProfile | null;
  setUser: (user: UserProfile) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      isAuthenticated: false,
      user: null,

      setUser: (user) => {
        set({
          user,
          isAuthenticated: true,
        });
      },

      logout: () => {
        set({ isAuthenticated: false, user: null });
      },
    }),
    {
      name: 'auth-storage',
    }
  )
);
