-- Trade Execution Data Table
CREATE TABLE executions (
    id SERIAL PRIMARY KEY,
    exec_id VARCHAR(50) NOT NULL,
    cl_ord_id VARCHAR(50) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    side CHAR(1) NOT NULL, -- 1: Buy, 2: Sell
    quantity INT NOT NULL,
    price DECIMAL(18, 4) NOT NULL,
    status CHAR(1) NOT NULL, -- 2: Filled, 8: Rejected
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Index for quick lookup by client order ID or execution ID
CREATE INDEX idx_executions_cl_ord_id ON executions(cl_ord_id);
CREATE INDEX idx_executions_exec_id ON executions(exec_id);
