import { useCallback, useRef, useEffect } from 'react';
import { useJobLogsInfinite } from '@lib/hooks/jobs.hook';
import {
  ShSheet,
  ShSheetContent,
  ShSheetHeader,
  ShSheetBody,
  ShSheetTitle,
  ShSheetDescription,
} from '@lib/components/sh-sheet/sheet.component';
import { ShBadge } from '@lib/components/sh-badge/badge.component';
import { ShSkeleton } from '@lib/components/sh-skeleton/skeleton.component';
import { ShButton } from '@lib/components/sh-button/button.component';
import { CheckCircle2, XCircle, Loader2, RefreshCw, ScrollText } from 'lucide-react';
import { cn } from '@lib/utils/cn.util';
import type { LogResponse } from '../jobs.type';

interface JobLogsSheetProps {
  jobId: number | null;
  jobName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

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
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const rem = seconds % 60;
  return `${minutes}m ${rem}s`;
};

const StatusIcon = ({ status }: { status: LogResponse['status'] }) => {
  if (status === 'SUCCESS') return <CheckCircle2 className="w-5 h-5 text-green-500 shrink-0" />;
  if (status === 'FAILED') return <XCircle className="w-5 h-5 text-destructive shrink-0" />;
  return <Loader2 className="w-5 h-5 text-blue-500 animate-spin shrink-0" />;
};

const statusBadgeVariant = (status: LogResponse['status']) => {
  if (status === 'SUCCESS') return 'success' as const;
  if (status === 'FAILED') return 'destructive' as const;
  return 'running' as const;
};

export const JobLogsSheet = ({ jobId, jobName, open, onOpenChange }: JobLogsSheetProps) => {
  const observerRef = useRef<IntersectionObserver | null>(null);

  const {
    data,
    isLoading,
    isError,
    refetch,
    isFetching,
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
    <ShSheet open={open} onOpenChange={onOpenChange}>
      <ShSheetContent side="right" className="sm:max-w-lg w-full flex flex-col p-0">
        <ShSheetHeader>
          <div className="flex items-center justify-between pr-8">
            <div>
              <ShSheetTitle className="flex items-center gap-2">
                <ScrollText className="w-4 h-4" />
                Histórico de Execuções
              </ShSheetTitle>
              <ShSheetDescription className="truncate mt-0.5">{jobName}</ShSheetDescription>
            </div>
            <ShButton
              variant="ghost"
              size="icon-sm"
              onClick={() => refetch()}
              disabled={isFetching}
            >
              <RefreshCw className={cn('w-4 h-4', isFetching && 'animate-spin')} />
            </ShButton>
          </div>
        </ShSheetHeader>

        <ShSheetBody className="p-0 overflow-y-auto">
          {isLoading ? (
            <div className="p-6 space-y-4">
              {Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="flex gap-3">
                  <ShSkeleton className="w-5 h-5 rounded-full shrink-0 mt-0.5" />
                  <div className="flex-1 space-y-2">
                    <ShSkeleton className="h-4 w-24" />
                    <ShSkeleton className="h-3 w-40" />
                    <ShSkeleton className="h-3 w-20" />
                  </div>
                </div>
              ))}
            </div>
          ) : isError ? (
            <div className="p-6 text-center text-sm text-destructive">
              Erro ao carregar logs. Verifique se o servidor está rodando.
            </div>
          ) : logs.length === 0 ? (
            <div className="p-10 flex flex-col items-center gap-3 text-muted-foreground">
              <ScrollText className="w-10 h-10 opacity-20" />
              <p className="text-sm">Nenhuma execução registrada ainda.</p>
              <p className="text-xs">Execute o job para ver o histórico aqui.</p>
            </div>
          ) : (
            <div className="divide-y">
              {logs.map((log) => (
                <div
                  key={log.id}
                  className="px-6 py-4 space-y-2 hover:bg-muted/20 transition-colors"
                >
                  <div className="flex items-start gap-3">
                    <StatusIcon status={log.status} />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <ShBadge variant={statusBadgeVariant(log.status)} className="text-xs">
                          {log.status}
                        </ShBadge>
                        <span className="text-xs text-muted-foreground">
                          {formatDuration(log.startedAt, log.finishedAt)}
                        </span>
                      </div>
                      <p className="text-xs text-muted-foreground mt-1">
                        Início: {formatDate(log.startedAt)}
                      </p>
                      {log.finishedAt && (
                        <p className="text-xs text-muted-foreground">
                          Fim: {formatDate(log.finishedAt)}
                        </p>
                      )}
                      {log.errorMessage && (
                        <div className="mt-2 rounded-md bg-destructive/10 border border-destructive/20 px-3 py-2">
                          <p className="text-xs font-mono text-destructive break-all">
                            {log.errorMessage}
                          </p>
                        </div>
                      )}
                    </div>
                  </div>
                </div>
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
        </ShSheetBody>
      </ShSheetContent>
    </ShSheet>
  );
};
