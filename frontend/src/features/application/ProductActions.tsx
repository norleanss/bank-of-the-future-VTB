import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CreditProduct, ProductMatch } from '../../shared/types/models';
import { getWorkspace, saveWorkspace } from '../../shared/lib/storage';
import { useCompany } from '../../app/useCompany';
import { ErrorState } from '../../shared/ui/Feedback';
import { Button } from '../../shared/ui/Button';

export function ProductActions({
  product,
  match,
}: {
  product: CreditProduct;
  match?: ProductMatch;
}) {
  const company = useCompany();
  const navigate = useNavigate();
  const [error, setError] = useState<unknown>(null);
  const apply = (subscription: boolean) => {
    try {
      const previous = getWorkspace(company?.id);
      const same = previous.applicationDraft.productCode === product.code;
      saveWorkspace(
        {
          applicationId: same ? previous.applicationId : undefined,
          applicationStep: 0,
          applicationDraft: {
            ...(same ? previous.applicationDraft : {}),
            ...previous.selection,
            companyId: company?.id,
            productCode: product.code,
            subscription,
            requestedAmount:
              previous.selection.requestedAmount ??
              (same ? previous.applicationDraft.requestedAmount : undefined) ??
              product.minAmount,
            term:
              previous.selection.term ??
              (same ? previous.applicationDraft.term : undefined) ??
              product.maxTerm,
            termUnit:
              previous.selection.termUnit ??
              (same ? previous.applicationDraft.termUnit : undefined) ??
              product.termUnit,
            purpose: previous.selection.purpose ?? 'WORKING_CAPITAL',
            fundingMode: previous.selection.fundingMode ?? product.fundingModes[0],
            repaymentType: product.repaymentTypes[0],
          },
        },
        company?.id,
      );
      navigate('/applications/new');
    } catch (err) {
      setError(err);
    }
  };
  return (
    <div className="fields" style={{ width: '100%', marginTop: 20 }}>
      <Button
        block
        view="primary"
        disabled={match ? !match.suitable : false}
        onClick={() => apply(false)}
      >
        Оформить
      </Button>
      {product.rateWithSubscription !== undefined && (
        <Button
          block
          disabled={match ? !match.suitable && !match.requiresSubscription : false}
          onClick={() => apply(true)}
        >
          Оформить с подпиской
        </Button>
      )}
      {error !== null && <ErrorState error={error} />}
    </div>
  );
}
