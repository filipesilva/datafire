# datascript-firebase

WIP

Other possible names, that make more sense if this lib ends up having functionality that doesn't exist in datascript like security rules and snapshot loading:
- firelog
- firescript
- firedata
- datafire
- datastore

Consider moving notes and todos here.

Notes:

- For datom granularity, don't need to group by tx when just getting latest snapshot. Add retracted field and update it in a cloud function to enable short snapshot via query.

- Keep only av on datom granularity transit data.

- On datom granularity, store read access info on the datom, update it on a cloud function that watches a special doc that lists access privileges. Can't just use security rule to filter because of https://stackoverflow.com/questions/56296046/firestore-access-documents-in-collection-user-has-permission-to.

- That covers datom reads, what about writes? Would need to have a security rule that says "user can only write tx that have this entity". Could use https://firebase.google.com/docs/firestore/security/rules-conditions#access_other_documents to check if the id exists on the acess collection.