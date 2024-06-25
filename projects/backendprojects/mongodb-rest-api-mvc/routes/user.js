const express = require("express");

const router = express.Router();
const {handleGetAllUsers, 
    handleGetUserById, 
    handleUpdateUserById, 
    handleCreateNewUser, 
    handleDeleteUserById} = require("../controllers/user");

// Routes
// router.get("/users", async(req, res) =>{
//     const allDbUsers = await User.find({});
//     const html = `
//         <ul>
//             ${allDbUsers.map((us)=>`<li>
//             ${us.firstName}-
//             ${us.email}
//                 </li>`).join("")
//             }
//         </ul>
//     `;
//     res.send(html);
//     //return res.json(users);
// });

// REST API
// GET
// for complete user list
router.route("/").get(handleGetAllUsers).post(handleCreateNewUser);

// for individual user list
router
.route("/:id")
.get(handleGetUserById)
.patch(handleUpdateUserById)
.delete(handleDeleteUserById);


module.exports = router;
