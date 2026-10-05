
`$link_opened` records runtime links and Journey open-link steps after the URL is handed to the browser or system. It carries `url`, `target`, `screen_id`, optional `instance_id`, and Journey leg attribution. Malformed or unopenable URLs do not record it. It forwards as `LinkOpened`, with wire name `link_opened`.
