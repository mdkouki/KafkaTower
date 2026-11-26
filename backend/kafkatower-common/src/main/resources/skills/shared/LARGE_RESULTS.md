# Large Results

Tool shows max 5 items of a list. More → total count, first 5 (snippet), **collection key**.
Full list kept few minutes.

- `bulkOperation(key, operation, expression, parameter)` works on whole list: `count`, `slice`,
  `all` (every item, only when answer needs every one), `chunk`, `filter`, `exclude`,
  `filter_number`, `sort`, `distinct`, `group_by`, `map`, `flat_map`, `aggregate`.
- Bulk result over 5 items → stored again, new key. Chain on newest key.
- Prefer op that answers directly (`count`, `group_by`, `aggregate`, `filter_number`) over paging.
- Orchestrator cannot open your keys. Read every item the question needs before replying. Put them
  in reply. Never write "not shown", "not listed", "N more". Never state a count you did not read.
- Key unknown/expired → rerun original tool.
