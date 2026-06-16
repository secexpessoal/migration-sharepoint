import { useCallback, useRef, useEffect } from 'react';
import { useJobLogsInfinite } from '@/lib/hooks/jobs.hook';
import type { JobStatus } from '@/routes/jobs/jobs.type';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetBody,
  SheetTitle,
  SheetDescription,
} from '@/lib/components/ui/sheet';
import { Badge } from '@/lib/components/ui/badge';
import { Skeleton } from '@/lib/components/ui/skeleton';
import { ScrollText, Loader2 } from 'lucide-react';

interface JobLogsSheetProps {
  jobId: number | null;
  jobName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const statusMeta: Record<
  JobStatus,
  {
    label: string;
    variant: 'default' | 'secondary' | 'running' | 'success' | 'warning' | 'destructive';
  }
> = {
  RUNNING: { label: 'Rodando', variant: 'running' },
  SUCCESS: { label: 'Sucesso', variant: 'success' },
  FAILED: { label: 'Falha', variant: 'destructive' },
};

const formatDateTime = (iso: string) =>
  new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(iso));

const formatDuration = (start: string, end?: string) => {
  if (!end) return '—';

  const ms = new Date(end).getTime() - new Date(start).getTime();

  if (ms < 1000) return `${ms}ms`;
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}s`;

  return `${Math.floor(ms / 60000)}m ${Math.floor((ms % 60000) / 1000)}s`;
};

export const JobLogsSheet = ({ jobId, jobName, open, onOpenChange }: JobLogsSheetProps) => {
  const observerRef = useRef<IntersectionObserver | null>(null);

  const {
    data,
    isLoading,
    isError,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useJobLogsInfinite(jobId ?? 0);

  const logs = data?.pages.flatMap((page) => page.content) ?? [];
  const totalElements = data?.pages[0]?.totalElements ?? 0;

  const lastElementRef = useCallback(
    (node: HTMLDivElement | null) => {
      if (isFetchingNextPage) return;
      if (observerRef.current) observerRef.current.disconnect();

      observerRef.current = new IntersectionObserver((entries) => {
        if (entries[0]?.isIntersecting && hasNextPage) {
          fetchNextPage();
        }
      });

      if (node) observerRef.current.observe(node);
    },
    [isFetchingNextPage, hasNextPage, fetchNextPage]
  );

  useEffect(() => {
    return () => {
      if (observerRef.current) observerRef.current.disconnect();
    };
  }, []);

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-xl w-full flex flex-col p-0">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            <ScrollText className="w-4 h-4" />
            Histórico de Execuções
          </SheetTitle>
          <SheetDescription className="truncate">{jobName}</SheetDescription>
        </SheetHeader>

        <SheetBody className="flex-1 overflow-y-auto px-6 py-4">
          {isLoading && (
            <div className="space-y-3">
              {Array.from({ length: 4 }).map((_, index) => (
                <div key={index} className="rounded-lg border p-3 space-y-2">
                  <div className="flex items-center justify-between">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-5 w-16" />
                  </div>
                  <Skeleton className="h-3 w-48" />
                </div>
              ))}
            </div>
          )}

          {isError && (
            <p className="text-sm text-destructive text-center py-8">
              Erro ao carregar logs. Verifique se o servidor está rodando.
            </p>
          )}

          {!isLoading && !isError && logs.length === 0 && (
            <div className="flex flex-col items-center gap-2 py-16 text-muted-foreground">
              <ScrollText className="w-10 h-10 opacity-20" />
              <p className="text-sm">Nenhuma execução registrada.</p>
            </div>
          )}

          {!isLoading && !isError && logs.length > 0 && (
            <div className="space-y-3">
              <p className="text-xs text-muted-foreground">{totalElements} execução(ões) encontrada(s)</p>
              {logs.map((log) => {
                const meta = statusMeta[log.status];
                return (
                  <div key={log.id} className="rounded-lg border p-3 space-y-1.5 text-sm">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-xs text-muted-foreground font-mono">#{log.id}</span>
                      <Badge variant={meta.variant}>{meta.label}</Badge>
                    </div>
                    <div className="text-xs text-muted-foreground">
                      Início: {formatDateTime(log.startedAt)}
                    </div>
                    {log.finishedAt && (
                      <div className="text-xs text-muted-foreground">
                        Fim: {formatDateTime(log.finishedAt)}
                      </div>
                    )}
                    <div className="text-xs text-muted-foreground">
                      Duração: {formatDuration(log.startedAt, log.finishedAt)}
                    </div>
                    {log.errorMessage && (
                      <div className="mt-2 rounded bg-destructive/10 px-2.5 py-2 text-xs text-destructive font-mono whitespace-pre-wrap break-all">
                        {log.errorMessage}
                      </div>
                    )}
                  </div>
                );
              })}

              {hasNextPage && (
                <div ref={lastElementRef} className="py-4 flex justify-center">
                  {isFetchingNextPage && (
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                      <Loader2 className="w-4 h-4 animate-spin" />
                      Carregando mais...
                    </div>
                  )}
                </div>
              )}

              {!hasNextPage && logs.length > 0 && (
                <p className="text-xs text-muted-foreground text-center py-4">
                  Todos os {totalElements} registros foram carregados
                </p>
              )}
            </div>
          )}
        </SheetBody>
      </SheetContent>
    </Sheet>
  );
};
