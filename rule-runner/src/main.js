import {createRunner} from './server.js';

const app = createRunner({
  token: process.env.QF_RULE_RUNNER_TOKEN,
  blobDir: process.env.QF_BLOB_DIR,
  poolOptions: process.env.QF_RULE_WORKER_LIMIT === undefined ? {} : {limit: Number(process.env.QF_RULE_WORKER_LIMIT)},
});
app.server.listen(Number(process.env.PORT ?? 8090), '0.0.0.0');
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await app.close(); process.exit(0); });
