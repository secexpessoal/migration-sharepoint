import { useState, useRef, useCallback, useEffect } from 'react';
import { useJobs, useJobLogsInfinite } from '@lib/hooks/jobs.hook';
import { ShSelect, ShSelectItem } from '@lib/components/sh-select/select.component';
import { ShButton } from '@lib/components/sh-button/button.component';
import { ShBadge } from '@lib/components/sh-badge/badge.component';
import { ShSkeleton } from '@lib/components/sh-skeleton/skeleton.component';
import { ShCard, ShCardContent } from '@lib/components/sh-card/card.component';
import { CheckCircle2, XCircle, Loader2, RefreshCw, ScrollText } from 'lucide-react';
import { cn } from '@lib/utils/cn.util';
import type { LogResponse } from '@routes/jobs/jobs.type';

const formatDate = (iso: string) =>
  new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(iso));

const formatDuration = (start: string, end?: string): string => {
  if (!end) return 'Em execução...';

  const ms = new Date(end).getTime() - new Date(start).getTime();
  if (ms < 0) return '—';

  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;

  return `${Math.floor(s / 60)}m ${s % 60}s`;
};

const StatusIcon = ({ status }: { status: LogResponse['status'] }) => {
  if (status === 'SUCCESS') return <CheckCircle2 className="w-5 h-5 text-green-500 shrink-0" />;
  if (status === 'FAILED') return <XCircle className="w-5 h-5 text-destructive shrink-0" />;
  return <Loader2 className="w-5 h-5 text-blue-500 animate-spin shrink-0" />;
};

const badgeVariant = (status: LogResponse['status']) => {
  if (status === 'SUCCESS') return 'success' as const;
  if (status === 'FAILED') return 'destructive' as const;
  return 'running' as const;
};

export const LogsRoute = () => {
  const [selectedJobId, setSelectedJobId] = useState<string>('');
  const observerRef = useRef<IntersectionObserver | null>(null);

  const { data: jobs, isLoading: loadingJobs } = useJobs();
  const {
    data,
    isLoading: loadingLogs,
    isError,
    refetch,
    isFetching,
    isFetchingNextPage,
    hasNextPage,
    fetchNextPage,
  } = useJobLogsInfinite(selectedJobId ? parseInt(selectedJobId) : 0);

  const logs = data?.pages.flatMap((page) => page.content) ?? [];
  const totalElements = data?.pages[0]?.totalElements ?? 0;

  const selectedJob = jobs?.find((j) => j.id === parseInt(selectedJobId));

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
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Audit Logs</h2>
          <p className="text-sm text-muted-foreground mt-0.5">Histórico de execuções por job.</p>
        </div>
        {selectedJobId && (
          <ShButton variant="outline" size="icon" onClick={() => refetch()} disabled={isFetching}>
            <RefreshCw className={cn('w-4 h-4', isFetching && 'animate-spin')} />
          </ShButton>
        )}
      </div>

      <div className="flex items-center gap-3">
        <div className="w-72">
          <ShSelect
            value={selectedJobId}
            onValueChange={setSelectedJobId}
            placeholder={loadingJobs ? 'Carregando jobs...' : 'Selecione um job...'}
            disabled={loadingJobs}
          >
            {jobs?.map((j) => (
              <ShSelectItem key={j.id} value={String(j.id)}>
                {j.name}
              </ShSelectItem>
            ))}
          </ShSelect>
        </div>
        {selectedJob && (
          <span className="text-xs text-muted-foreground">
            {selectedJob.targetDb} → {selectedJob.migration?.tableName || 'root'}
          </span>
        )}
      </div>

      {!selectedJobId && (
        <ShCard className="border-dashed">
          <ShCardContent className="py-16 flex flex-col items-center gap-3 text-muted-foreground">
            <ScrollText className="w-12 h-12 opacity-20" />
            <p className="text-sm">Selecione um job acima para ver o histórico de execuções.</p>
          </ShCardContent>
        </ShCard>
      )}

      {selectedJobId && loadingLogs && (
        <div className="space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <ShCard key={i}>
              <ShCardContent className="py-4 flex gap-4 items-start">
                <ShSkeleton className="w-5 h-5 rounded-full shrink-0" />
                <div className="flex-1 space-y-2">
                  <ShSkeleton className="h-4 w-24" />
                  <ShSkeleton className="h-3 w-48" />
                  <ShSkeleton className="h-3 w-32" />
                </div>
              </ShCardContent>
            </ShCard>
          ))}
        </div>
      )}

      {selectedJobId && isError && (
        <ShCard className="border-destructive/30">
          <ShCardContent className="py-10 text-center text-sm text-destructive">
            Erro ao carregar logs. Verifique se o servidor está rodando.
          </ShCardContent>
        </ShCard>
      )}

      {selectedJobId && !loadingLogs && !isError && logs.length === 0 && (
        <ShCard className="border-dashed">
          <ShCardContent className="py-14 flex flex-col items-center gap-3 text-muted-foreground">
            <ScrollText className="w-10 h-10 opacity-20" />
            <p className="text-sm">Nenhuma execução registrada para este job.</p>
            <p className="text-xs">Execute o job para ver o histórico aqui.</p>
          </ShCardContent>
        </ShCard>
      )}

      {selectedJobId && !loadingLogs && logs.length > 0 && (
        <div className="space-y-3">
          <p className="text-xs text-muted-foreground">{totalElements} execução(ões) encontrada(s)</p>
          {logs.map((log) => (
            <ShCard key={log.id} className="overflow-hidden">
              <ShCardContent className="py-0">
                <div className="flex gap-4 py-4">
                  <StatusIcon status={log.status} />
                  <div className="flex-1 min-w-0 space-y-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <ShBadge variant={badgeVariant(log.status)}>{log.status}</ShBadge>
                      <span className="text-xs font-medium text-muted-foreground">
                        {formatDuration(log.startedAt, log.finishedAt)}
                      </span>
                      <span className="text-xs text-muted-foreground ml-auto">#{log.id}</span>
                    </div>
                    <div className="text-xs text-muted-foreground space-y-0.5">
                      <p>Início: {formatDate(log.startedAt)}</p>
                      {log.finishedAt && <p>Fim: {formatDate(log.finishedAt)}</p>}
                    </div>
                    {log.errorMessage && (
                      <div className="mt-2 rounded-md bg-destructive/10 border border-destructive/20 px-3 py-2">
                        <p className="text-xs font-mono text-destructive break-all leading-relaxed">
                          {log.errorMessage}
                        </p>
                      </div>
                    )}
                  </div>
                </div>
              </ShCardContent>
            </ShCard>
          ))}

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
    </div>
  );
};
