import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { formatCents } from '../money';

const POLL_MS = 5000;
const RESTOCK_QTY = 10;

export function Dashboard() {
  const queryClient = useQueryClient();
  const summary = useQuery({ queryKey: ['summary'], queryFn: api.summary, refetchInterval: POLL_MS });
  const sales = useQuery({ queryKey: ['sales'], queryFn: api.sales, refetchInterval: POLL_MS });
  const alerts = useQuery({ queryKey: ['alerts'], queryFn: api.alerts, refetchInterval: POLL_MS });
  const products = useQuery({ queryKey: ['products'], queryFn: api.products, refetchInterval: POLL_MS });

  const restock = useMutation({
    mutationFn: (sku: string) => api.restock(sku, RESTOCK_QTY),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['products'] }),
  });

  const lowCount = products.data?.filter((p) => p.lowStock).length ?? 0;

  return (
    <div className="dashboard">
      <div className="tiles">
        <div className="panel tile">
          <span className="muted">Sales today</span>
          <strong>{summary.data?.count ?? '–'}</strong>
        </div>
        <div className="panel tile">
          <span className="muted">Revenue today</span>
          <strong>{summary.data ? formatCents(summary.data.totalCents) : '–'}</strong>
        </div>
        <div className="panel tile">
          <span className="muted">Low-stock products</span>
          <strong className={lowCount > 0 ? 'low' : undefined}>{products.data ? lowCount : '–'}</strong>
        </div>
      </div>

      <section className="panel">
        <h2>Stock</h2>
        {restock.isError && <p className="error">{restock.error.message}</p>}
        <table>
          <thead>
            <tr>
              <th>Product</th>
              <th className="num">Price</th>
              <th className="num">Stock</th>
              <th className="num hide-sm">Threshold</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {products.data?.map((p) => (
              <tr key={p.sku} className={p.lowStock ? 'low-row' : undefined}>
                <td>
                  {p.name} <span className="muted mono hide-sm">{p.sku}</span>
                </td>
                <td className="num">{formatCents(p.priceCents)}</td>
                <td className="num">{p.stock}</td>
                <td className="num hide-sm">{p.lowStockThreshold}</td>
                <td className="num">
                  <button type="button" disabled={restock.isPending} onClick={() => restock.mutate(p.sku)}>
                    +{RESTOCK_QTY}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <div className="two-col">
        <section className="panel">
          <h2>Low-stock alerts</h2>
          {alerts.data?.length === 0 && <p className="muted">No alerts yet.</p>}
          <ul className="plain">
            {alerts.data?.map((a) => (
              <li key={a.id}>
                <span className="low">●</span> <span className="mono">{a.sku}</span> down to {a.remaining}
                <span className="muted"> · {new Date(a.createdAt).toLocaleTimeString()}</span>
              </li>
            ))}
          </ul>
        </section>
        <section className="panel">
          <h2>Recent sales</h2>
          {sales.data?.length === 0 && <p className="muted">No sales today.</p>}
          <table>
            <tbody>
              {sales.data?.map((s) => (
                <tr key={s.id}>
                  <td className="muted">{new Date(s.createdAt).toLocaleTimeString()}</td>
                  <td>{s.lines.reduce((n, l) => n + l.quantity, 0)} items</td>
                  <td>{s.tenderType}</td>
                  <td className="num">{formatCents(s.totalCents)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </div>
  );
}
