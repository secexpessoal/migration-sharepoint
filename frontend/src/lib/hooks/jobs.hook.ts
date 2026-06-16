import { useQuery, useMutation, useQueryClient, useInfiniteQuery } from '@tanstack/react-query';
import { jobsService } from '@lib/services/jobs.service';
import type { JobRequest } from '@routes/jobs/jobs.type';

export const useJobs = () => {
  return useQuery({
    queryKey: ['jobs'],
    queryFn: jobsService.getAll,
  });
};

export const useJob = (id: number) => {
  return useQuery({
    queryKey: ['jobs', id],
    queryFn: () => jobsService.getById(id),
    enabled: !!id,
  });
};

export const useCreateJob = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: jobsService.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
    },
  });
};

export const useUpdateJob = (id: number) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (job: JobRequest) => jobsService.update(id, job),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
      queryClient.invalidateQueries({ queryKey: ['jobs', id] });
    },
  });
};

export const useDeleteJob = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: jobsService.delete,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['jobs'] });
    },
  });
};

export const useRunJob = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: jobsService.run,
    onSuccess: (_, id) => {
      queryClient.invalidateQueries({ queryKey: ['jobs', id, 'logs'] });
    },
  });
};

export const useJobLogs = (id: number) => {
  return useQuery({
    queryKey: ['jobs', id, 'logs'],
    queryFn: () => jobsService.getLogs(id),
    enabled: !!id,
  });
};

export const useJobLogsInfinite = (id: number, pageSize = 20) => {
  return useInfiniteQuery({
    queryKey: ['jobs', id, 'logs', 'infinite'],
    queryFn: ({ pageParam = 0 }) => jobsService.getLogsPaged(id, { page: pageParam, size: pageSize }),
    getNextPageParam: (lastPage) => {
      if (lastPage.last) return undefined;
      return lastPage.number + 1;
    },
    initialPageParam: 0,
    enabled: !!id,
  });
};
