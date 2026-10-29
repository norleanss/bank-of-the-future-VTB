import { useMutation } from '@tanstack/react-query';
import { creditApi } from '../../shared/api';
import type { UploadedDocument } from '../../shared/types/models';
import { documentLabels } from '../../shared/lib/applicationFields';
import { date } from '../../shared/lib/format';
import { ErrorState } from '../../shared/ui/Feedback';
import { Button } from '../../shared/ui/Button';

export function DocumentList({
  id,
  documents,
  backoffice = false,
}: {
  id: string;
  documents: UploadedDocument[];
  backoffice?: boolean;
}) {
  const download = useMutation({
    mutationFn: async (doc: UploadedDocument) => {
      const blob = await creditApi.getDocumentContent(id, doc.id, backoffice);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = doc.fileName;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
    },
  });
  return (
    <>
      {documents.length ? (
        <ul className="documentList">
          {documents.map((doc) => (
            <li key={doc.id}>
              <div>
                <strong>{doc.fileName}</strong>
                <p className="muted">
                  {documentLabels[doc.documentType]} ·{' '}
                  {Math.max(1, Math.ceil(doc.sizeBytes / 1024))} КБ · {date(doc.uploadedAt)}
                </p>
              </div>
              <Button
                size={40}
                loading={download.isPending && download.variables?.id === doc.id}
                disabled={download.isPending}
                aria-label={`Скачать ${doc.fileName}`}
                onClick={() => download.mutate(doc)}
              >
                Скачать
              </Button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted">Документы ещё не загружены.</p>
      )}
      {download.isError && <ErrorState error={download.error} />}
    </>
  );
}
