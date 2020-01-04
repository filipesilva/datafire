# datascript-firebase

WIP

Other possible names, that make more sense if this lib ends up having functionality that doesn't exist in datascript like security rules and snapshot loading:
- firelog
- firescript
- firedata
- datafire
- datastore

Notes:

- For datom granularity, don't need to group by tx when just getting latest snapshot. Add retracted field and update it in a cloud function to enable short snapshot via query.

- Keep only av on datom granularity transit data.

- If the rules deny access to any of the specified document paths, the entire request fails. https://firebase.google.com/docs/firestore/security/get-started and https://youtu.be/eW5MdE3ZcAw?t=866.

- On datom granularity, store read access info on the datom, update it on a cloud function that watches a special doc that lists access privileges. Can't just use security rule to filter because of https://stackoverflow.com/questions/56296046/firestore-access-documents-in-collection-user-has-permission-to. Test if this works before investing time in it.

- That covers datom reads, what about writes? Would need to have a security rule that says "user can only write tx that have this entity". Could use https://firebase.google.com/docs/firestore/security/rules-conditions#access_other_documents to check if the id exists on the acess collection.

- What happens to offline writes that fail security rules? Are they removed on a snapshot update?

Notes for https://tonsky.me/blog/datascript-internals/:

- Try using the datom eavt array format with added bool flag, get around the need for t somehow. Make a size comparison for large transit. Consider if needed in the datom granularity.

- Roam folks mentioned it was too slow to load all tx, maybe the slowness is ds loading all individual ones and adding indexes etc repeatedly? If so it might be worth it to still load individual tx but transact them as a single giant one.

- The non-temp IDs seem to be called implicit IDs.

- It's safe to use op vectors constructed from datoms, but TxReport is the only place where you can see datoms with added == false for datoms which were retracted.

- Filtered DBs sound interesting for ACL purposes. Maybe a similar concept can be used for datom granularity df links, but the filtering is happening on fb, maybe even a fb function for more involved filters.


TODOS:
- test/figure out retracts `[1 :name "Ivan" 536870918 false]`
  - negative tx means retract
- figure out other ds built-ins ever appear as the op in tx-datoms (see builtin-fn?)
- add spec to validate data coming in and out
- really need to revisit tx/tx-data/ops names
- add error-cbs to transact!
- after I have tests, check if it's ok to add tempid info on fb doc
- consider adding docs that transact! returns a promise with the doc (and thus seid), but that it only resolves when it hits the fb server. Offline docs say this shouldn't be relied on overall and it's better to not wait on this promise.
- support tx-meta on transact!
- test permissions model
- put df in an alpha namespace?