const mongoose = require("mongoose");

async function connectMonogoDB(url) {
    return mongoose.connect(url)
    .then(()=> console.log("Mongo DB connected"))
    .catch((err)=> console.log("Mongo error",err));
}

module.exports = {
    connectMonogoDB,
}